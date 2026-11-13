package ch.benedict.m321.chatservice.service;

import ch.benedict.m321.chatservice.dto.ChatMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
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
 */
@Component
@Slf4j
public class LiveDeliveryState {

    /** Eine offene Verbindung: welcher Raum, und worüber wir zu ihr senden. */
    private record Connection(UUID roomId, SseEmitter emitter) {
    }

    private final ReplayBuffer replayBuffer = new ReplayBuffer();

    private final List<Connection> connections = new ArrayList<>();

    /**
     * Nimmt eine Nachricht vom Broker entgegen und gibt sie an alle
     * Verbindungen desselben Raums weiter.
     *
     * Reihenfolge mit Absicht: erst in den Puffer, dann senden. Scheitert das
     * Senden an einem Client, kann dieser die Nachricht später nachholen.
     */
    public synchronized void deliver(ChatMessage message) {
        boolean isNew = replayBuffer.add(message);
        if (!isNew) {
            log.debug("Message {} already known, not delivered again", message.id());
            return;
        }
        Iterator<Connection> iterator = connections.iterator();
        while (iterator.hasNext()) {
            Connection connection = iterator.next();
            if (!connection.roomId().equals(message.roomId())) {
                continue;
            }
            boolean sent = sendTo(connection.emitter(), message);
            if (!sent) {
                iterator.remove();
            }
        }
    }

    /**
     * Meldet eine neue Verbindung an. Mit Cursor wird zuerst nachgeholt, was
     * verpasst wurde, dann läuft dieselbe Verbindung live weiter. Beides
     * geschieht unter demselben Schloss wie deliver, darum gibt es keine Lücke
     * und kein Doppeltes.
     *
     * @return false, wenn der Cursor unbekannt ist (der Endpunkt antwortet dann
     *         mit 409). Dann wurde nichts angemeldet. Sonst true.
     */
    public synchronized boolean subscribe(UUID roomId, UUID cursorId, SseEmitter emitter) {
        if (cursorId != null) {
            boolean cursorKnown = replayBuffer.knowsCursor(cursorId, roomId);
            if (!cursorKnown) {
                log.info("Cursor {} unknown for room {}, subscription refused", cursorId, roomId);
                return false;
            }
            boolean replayed = replayMissedMessages(roomId, cursorId, emitter);
            if (!replayed) {
                return true;
            }
        }
        Connection connection = new Connection(roomId, emitter);
        connections.add(connection);
        registerCleanup(emitter);
        log.info("Connection subscribed to room {}, {} open", roomId, connections.size());
        return true;
    }

    /** Sendet die verpassten Nachrichten. Gibt false zurück, wenn der Client dabei wegbricht. */
    private boolean replayMissedMessages(UUID roomId, UUID cursorId, SseEmitter emitter) {
        List<ChatMessage> missed = replayBuffer.messagesAfter(cursorId, roomId);
        for (ChatMessage message : missed) {
            boolean sent = sendTo(emitter, message);
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
    private void registerCleanup(SseEmitter emitter) {
        // Ein Lambda ist hier erlaubt (Spezifikation 7, Punkt 8): es ist die kürzeste lesbare Form.
        emitter.onCompletion(() -> remove(emitter));
        emitter.onTimeout(() -> remove(emitter));
        emitter.onError(error -> remove(emitter));
    }

    /** Nimmt die Verbindung aus der Liste. Wird von den drei Rückrufen aufgerufen. */
    private synchronized void remove(SseEmitter emitter) {
        Iterator<Connection> iterator = connections.iterator();
        while (iterator.hasNext()) {
            Connection connection = iterator.next();
            if (connection.emitter() == emitter) {
                iterator.remove();
            }
        }
    }

    /**
     * Schreibt eine Nachricht als ein SSE-Ereignis: id: die UUID, data: das JSON.
     * Ein Sendefehler beendet nur diese Verbindung, nie die Zustellung an andere.
     *
     * @return true, wenn gesendet wurde, false bei einem Fehler
     */
    private boolean sendTo(SseEmitter emitter, ChatMessage message) {
        String eventId = message.id().toString();
        SseEmitter.SseEventBuilder event = SseEmitter.event().id(eventId).data(message, MediaType.APPLICATION_JSON);
        try {
            emitter.send(event);
            return true;
        } catch (IOException | IllegalStateException exception) {
            log.info("Connection to room {} failed on message {}: {}",
                    message.roomId(), eventId, exception.getClass().getSimpleName());
            emitter.completeWithError(exception);
            return false;
        }
    }
}
