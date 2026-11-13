package ch.benedict.m321.chatservice.controller;

import ch.benedict.m321.chatservice.service.LiveDeliveryState;
import ch.benedict.m321.chatservice.service.SubscribeResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.UUID;

/**
 * Der Anzeigeweg: ein Client öffnet GET /stream?roomId=... und bekommt jede
 * neue Nachricht seines Raums sofort als Server-Sent Event.
 *
 * Wie das ganze Modul ist auch dieser Endpunkt nur aus dem Docker-Netz
 * erreichbar und prüft kein Token (siehe MessageController).
 */
@RestController
@RequiredArgsConstructor
public class StreamController {

    /**
     * Nach 30 Minuten beendet der Server die Verbindung. Der Browser
     * verbindet dann mit Last-Event-ID neu und holt nach, was fehlt
     * (Spezifikation 7, Punkt 2).
     */
    private static final long TIMEOUT_IN_MILLISECONDS = 1_800_000L;

    private final LiveDeliveryState liveDeliveryState;

    /**
     * Öffnet einen Stream für einen Raum.
     *
     * Eine fehlende oder ungültige roomId meldet Spring selbst als 400. Eine
     * ungültige Last-Event-ID melden wir ebenfalls als 400. Eine gültige, aber
     * unbekannte ergibt 409: lieber eine Lücke melden, als sie zu verschweigen
     * (Spezifikation 2.2 und 4.6).
     *
     * @param roomId      der Raum, dessen Nachrichten der Client sehen will
     * @param lastEventId die ID der letzten Nachricht, die der Client schon hat (optional)
     */
    @GetMapping("/stream")
    public SseEmitter stream(
            @RequestParam UUID roomId,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
        UUID cursorId = parseCursor(lastEventId);
        SseEmitter emitter = new SseEmitter(TIMEOUT_IN_MILLISECONDS);
        announceConnection(emitter);

        SubscribeResult result = liveDeliveryState.subscribe(roomId, cursorId, emitter);
        if (result == SubscribeResult.UNKNOWN_CURSOR) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Last-Event-ID unbekannt, bitte ohne Cursor neu verbinden");
        }
        return emitter;
    }

    /**
     * Schickt als Erstes eine Kommentarzeile (": connected"). Ohne sie
     * verschickt der Server die Antwortköpfe erst mit dem ersten Ereignis, und
     * ein Client in einem ruhigen Raum wüsste nicht, ob die Verbindung steht.
     * Eine Kommentarzeile hat weder id: noch data: und ändert den Vertrag nicht.
     */
    private void announceConnection(SseEmitter emitter) {
        SseEmitter.SseEventBuilder comment = SseEmitter.event().comment("connected");
        try {
            emitter.send(comment);
        } catch (IOException exception) {
            throw new IllegalStateException("Verbindung konnte nicht eröffnet werden", exception);
        }
    }

    /** Wandelt den Header in eine UUID um. Ohne Header gibt es keinen Cursor (null). */
    private UUID parseCursor(String lastEventId) {
        if (lastEventId == null) {
            return null;
        }
        try {
            return UUID.fromString(lastEventId);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Last-Event-ID ist keine UUID");
        }
    }
}
