package ch.benedict.m321.chatservice.service;

import ch.benedict.m321.chatservice.dto.ChatMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Der gemeinsame Zustand einer Instanz: wer ist gerade verbunden, und was
 * kam zuletzt an.
 *
 * Warum alles in EINER Klasse hinter EINEM Schloss (synchronized): Anmelden,
 * Replay, UUID-Prüfung und Verteilen dürfen sich nicht überschneiden. Sonst
 * könnte eine Nachricht genau zwischen dem Replay und der Anmeldung
 * eintreffen und ginge verloren, oder sie käme doppelt an (Spezifikation 4.6).
 * Die Last einer Instanz (ein Consumer, wenige Clients) trägt ein Schloss
 * locker, und dafür lässt es sich in einem Satz erklären.
 *
 * Ein Client, der nie liest, hält das Schloss höchstens so lange, wie Tomcat
 * auf das Schreiben wartet (server.tomcat.connection-timeout, application.yml).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class LiveDeliveryState {

    private final ObjectMapper objectMapper;

    private final ReplayBuffer replayBuffer = new ReplayBuffer();

    /** Pro Raum die offenen Verbindungen, damit eine Nachricht nur ihren Raum durchläuft. */
    private final Map<UUID, List<SseEmitter>> emittersByRoom = new HashMap<>();

    /**
     * Nimmt eine Nachricht vom Broker entgegen und gibt sie an alle
     * Verbindungen desselben Raums weiter.
     *
     * Reihenfolge mit Absicht: erst das JSON, dann in den Puffer, dann senden.
     * Das JSON entsteht EINMAL und liegt mit der Nachricht im Puffer, so senden
     * Live-Zustellung und Replay denselben Text. Scheitert das Senden an einem
     * Client, kann dieser die Nachricht später nachholen.
     */
    public synchronized void deliver(ChatMessage message) {
        String json = toJson(message);
        if (json == null) {
            return;
        }
        BufferedMessage buffered = new BufferedMessage(message, json);
        boolean isNew = replayBuffer.add(buffered);
        if (!isNew) {
            log.debug("Message {} already known, not delivered again", message.id());
            return;
        }
        List<SseEmitter> emittersInRoom = emittersByRoom.get(message.roomId());
        if (emittersInRoom == null) {
            return;
        }
        // Eine Kopie, weil ein fehlgeschlagener Client aus der echten Liste entfernt wird.
        List<SseEmitter> snapshot = new ArrayList<>(emittersInRoom);
        for (SseEmitter emitter : snapshot) {
            boolean sent = sendTo(emitter, buffered);
            if (!sent) {
                // Sofort entfernen: die Callbacks aus registerCleanup kommen erst später,
                // und bis dahin bekäme die tote Verbindung jede weitere Nachricht.
                remove(message.roomId(), emitter);
            }
        }
    }

    /**
     * Meldet eine neue Verbindung an. Mit Cursor wird zuerst nachgeholt, was
     * verpasst wurde, dann läuft dieselbe Verbindung live weiter. Beides
     * geschieht unter demselben Schloss wie deliver, darum gibt es keine Lücke
     * und kein Doppeltes.
     *
     * @return SUBSCRIBED, UNKNOWN_CURSOR (der Endpunkt antwortet mit 409, es wurde
     *         nichts angemeldet) oder REPLAY_FAILED (der Client brach beim Nachholen weg)
     */
    public synchronized SubscribeResult subscribe(UUID roomId, UUID cursorId, SseEmitter emitter) {
        if (cursorId != null) {
            boolean cursorKnown = replayBuffer.knowsCursor(cursorId, roomId);
            if (!cursorKnown) {
                log.info("Cursor {} unknown for room {}, subscription refused", cursorId, roomId);
                return SubscribeResult.UNKNOWN_CURSOR;
            }
            boolean replayed = replayMissedMessages(roomId, cursorId, emitter);
            if (!replayed) {
                return SubscribeResult.REPLAY_FAILED;
            }
        }
        List<SseEmitter> emittersInRoom = emittersByRoom.get(roomId);
        if (emittersInRoom == null) {
            emittersInRoom = new ArrayList<>();
            emittersByRoom.put(roomId, emittersInRoom);
        }
        emittersInRoom.add(emitter);
        registerCleanup(roomId, emitter);
        log.info("Connection subscribed to room {}, {} open in this room", roomId, emittersInRoom.size());
        return SubscribeResult.SUBSCRIBED;
    }

    /** Sendet die verpassten Nachrichten. Gibt false zurück, wenn der Client dabei wegbricht. */
    private boolean replayMissedMessages(UUID roomId, UUID cursorId, SseEmitter emitter) {
        List<BufferedMessage> missed = replayBuffer.messagesAfter(cursorId, roomId);
        for (BufferedMessage buffered : missed) {
            boolean sent = sendTo(emitter, buffered);
            if (!sent) {
                return false;
            }
        }
        return true;
    }

    /**
     * Sorgt dafür, dass eine Verbindung aus der Liste verschwindet, wenn sie
     * endet, abläuft oder einen Fehler meldet. Sonst blieben tote Verbindungen
     * für immer im Speicher (Spezifikation 4.7).
     */
    private void registerCleanup(UUID roomId, SseEmitter emitter) {
        // Ein Lambda ist hier erlaubt (Spezifikation 7, Punkt 8): es ist die kürzeste lesbare Form.
        emitter.onCompletion(() -> remove(roomId, emitter));
        emitter.onTimeout(() -> remove(roomId, emitter));
        emitter.onError(error -> remove(roomId, emitter));
    }

    /** Nimmt die Verbindung aus der Liste ihres Raums. Ein leerer Raum verschwindet ganz. */
    private synchronized void remove(UUID roomId, SseEmitter emitter) {
        List<SseEmitter> emittersInRoom = emittersByRoom.get(roomId);
        if (emittersInRoom == null) {
            return;
        }
        emittersInRoom.remove(emitter);
        if (emittersInRoom.isEmpty()) {
            emittersByRoom.remove(roomId);
        }
    }

    /**
     * Wandelt die Nachricht in das JSON um, das als data: hinausgeht.
     *
     * @return das JSON, oder null, falls die Umwandlung scheitert (dann wird die Nachricht verworfen:
     *         sie kommt nicht in den Puffer und geht an niemanden)
     */
    private String toJson(ChatMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException exception) {
            log.error("Message {} could not be written as JSON: {}", message.id(), exception.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * Schreibt eine Nachricht als ein SSE-Ereignis: id: die UUID, data: das JSON.
     * JEDER Fehler beendet nur diese eine Verbindung, nie die Zustellung an andere.
     * Auch ein unerwarteter RuntimeException (zum Beispiel aus einem Konverter) gehört
     * dazu, sonst bekämen die übrigen Clients die Nachricht nie (siehe Review).
     *
     * @return true, wenn gesendet wurde, false bei einem Fehler
     */
    private boolean sendTo(SseEmitter emitter, BufferedMessage buffered) {
        ChatMessage message = buffered.message();
        String eventId = message.id().toString();
        String json = buffered.json();
        SseEmitter.SseEventBuilder event = SseEmitter.event().id(eventId).data(json, MediaType.APPLICATION_JSON);
        try {
            emitter.send(event);
            return true;
        } catch (IOException | RuntimeException exception) {
            log.info("Connection to room {} failed on message {}: {}",
                    message.roomId(), eventId, exception.getClass().getSimpleName());
            emitter.completeWithError(exception);
            return false;
        }
    }
}
