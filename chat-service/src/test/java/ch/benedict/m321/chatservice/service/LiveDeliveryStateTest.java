package ch.benedict.m321.chatservice.service;

import ch.benedict.m321.chatservice.dto.ChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prüft Verteilung, Replay und Fehlerfälle von LiveDeliveryState ohne
 * Broker und ohne HTTP. Das ist der heikelste Teil der Live-Zustellung,
 * darum bekommt er die genauesten Tests (Spezifikation 4.1, 4.2, 4.6, 4.7).
 */
class LiveDeliveryStateTest {

    private static final UUID ROOM_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID ROOM_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    /**
     * Ein SseEmitter, der mitschreibt, welche Nachrichten-IDs er bekommen hat,
     * und auf Wunsch beim Senden eine IOException wirft. Kein Mock: es ist ein
     * echter SseEmitter mit einer überschriebenen Methode.
     */
    static class RecordingEmitter extends SseEmitter {

        final List<String> receivedIds = new ArrayList<>();
        boolean failOnSend = false;
        boolean failWithRuntimeException = false;

        /** Ohne Zeitlimit-Argument, im Test läuft nichts ab. */
        RecordingEmitter() {
            super(60_000L);
        }

        /** Merkt sich die ID jedes Ereignisses, statt es an einen Client zu schreiben. */
        @Override
        public void send(SseEventBuilder builder) throws IOException {
            if (failOnSend) {
                throw new IOException("client is gone");
            }
            if (failWithRuntimeException) {
                throw new IllegalArgumentException("converter failed");
            }
            Set<ResponseBodyEmitter.DataWithMediaType> parts = builder.build();
            for (ResponseBodyEmitter.DataWithMediaType part : parts) {
                Object data = part.getData();
                if (data instanceof String text && text.startsWith("id:")) {
                    // Der Text lautet "id:<uuid>\ndata:", wir brauchen nur die UUID.
                    int endOfId = text.indexOf('\n');
                    String id = text.substring(3, endOfId);
                    receivedIds.add(id);
                }
            }
        }
    }

    /** Baut den Zustand mit einem ObjectMapper, der Instant schreiben kann, wie der von Spring Boot. */
    private LiveDeliveryState newState() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        return new LiveDeliveryState(objectMapper);
    }

    /** Die ID einer Nachricht als Text, so wie sie im Ereignis (id:) steht. */
    private String idOf(ChatMessage message) {
        UUID id = message.id();
        return id.toString();
    }

    /** Baut eine Testnachricht für einen Raum. */
    private ChatMessage messageIn(UUID roomId) {
        UUID id = UUID.randomUUID();
        return new ChatMessage(id, roomId, "anna", "Anna Muster", "Hallo", Instant.now());
    }

    /** Eine Nachricht für Raum A erreicht genau die Verbindungen in Raum A. */
    @Test
    void deliversOnlyToConnectionsInTheSameRoom() {
        LiveDeliveryState state = newState();
        RecordingEmitter roomA1 = new RecordingEmitter();
        RecordingEmitter roomA2 = new RecordingEmitter();
        RecordingEmitter roomB = new RecordingEmitter();
        state.subscribe(ROOM_A, null, roomA1);
        state.subscribe(ROOM_A, null, roomA2);
        state.subscribe(ROOM_B, null, roomB);
        ChatMessage message = messageIn(ROOM_A);

        state.deliver(message);

        List<String> expected = List.of(idOf(message));
        assertEquals(expected, roomA1.receivedIds);
        assertEquals(expected, roomA2.receivedIds);
        assertTrue(roomB.receivedIds.isEmpty());
    }

    /** Dieselbe UUID zweimal ergibt nur ein Ereignis (4.1, Schritt 3a). */
    @Test
    void ignoresTheSameMessageDeliveredTwice() {
        LiveDeliveryState state = newState();
        RecordingEmitter emitter = new RecordingEmitter();
        state.subscribe(ROOM_A, null, emitter);
        ChatMessage message = messageIn(ROOM_A);

        state.deliver(message);
        state.deliver(message);

        assertEquals(1, emitter.receivedIds.size());
    }

    /** Mit Cursor kommt erst der Replay der späteren Nachrichten, dann live. */
    @Test
    void replaysMissedMessagesAndThenGoesLive() {
        LiveDeliveryState state = newState();
        ChatMessage cursor = messageIn(ROOM_A);
        ChatMessage missed1 = messageIn(ROOM_A);
        ChatMessage missed2 = messageIn(ROOM_A);
        state.deliver(cursor);
        state.deliver(missed1);
        state.deliver(missed2);
        RecordingEmitter emitter = new RecordingEmitter();

        UUID cursorId = cursor.id();
        SubscribeResult result = state.subscribe(ROOM_A, cursorId, emitter);
        ChatMessage live = messageIn(ROOM_A);
        state.deliver(live);

        assertEquals(SubscribeResult.SUBSCRIBED, result);
        List<String> expected = List.of(idOf(missed1), idOf(missed2), idOf(live));
        assertEquals(expected, emitter.receivedIds);
    }

    /** Ein unbekannter Cursor wird gemeldet und meldet NICHTS an (Grundlage für 409). */
    @Test
    void refusesAnUnknownCursorAndDoesNotSubscribe() {
        LiveDeliveryState state = newState();
        RecordingEmitter emitter = new RecordingEmitter();

        UUID unknownId = UUID.randomUUID();
        SubscribeResult result = state.subscribe(ROOM_A, unknownId, emitter);
        ChatMessage afterwards = messageIn(ROOM_A);
        state.deliver(afterwards);

        assertEquals(SubscribeResult.UNKNOWN_CURSOR, result);
        assertTrue(emitter.receivedIds.isEmpty());
    }

    /**
     * Eine Verbindung, die beim Senden wirft, wird entfernt. Die andere
     * bekommt die Nachricht trotzdem, und sie steht im Puffer, damit der
     * kaputte Client sie später nachholen kann (4.7).
     */
    @Test
    void aFailingConnectionDoesNotStopTheOthers() {
        LiveDeliveryState state = newState();
        RecordingEmitter broken = new RecordingEmitter();
        RecordingEmitter healthy = new RecordingEmitter();
        ChatMessage cursor = messageIn(ROOM_A);
        state.deliver(cursor);
        state.subscribe(ROOM_A, null, broken);
        state.subscribe(ROOM_A, null, healthy);
        broken.failOnSend = true;
        ChatMessage message = messageIn(ROOM_A);

        state.deliver(message);
        broken.failOnSend = false;
        ChatMessage next = messageIn(ROOM_A);
        state.deliver(next);

        List<String> expectedIds = List.of(idOf(message), idOf(next));
        assertEquals(expectedIds, healthy.receivedIds);
        assertTrue(broken.receivedIds.isEmpty(), "die kaputte Verbindung wurde entfernt");
        RecordingEmitter comesBack = new RecordingEmitter();
        UUID cursorId = cursor.id();
        state.subscribe(ROOM_A, cursorId, comesBack);
        assertEquals(expectedIds, comesBack.receivedIds);
    }

    /**
     * Auch ein unerwarteter RuntimeException beim Senden (zum Beispiel aus einem
     * Konverter) beendet nur diese Verbindung: die dahinter bekommen die Nachricht
     * trotzdem. Vorher brach deliver() dabei ab und die übrigen Clients gingen leer aus.
     */
    @Test
    void aRuntimeExceptionOnOneConnectionDoesNotStopTheOthers() {
        LiveDeliveryState state = newState();
        RecordingEmitter broken = new RecordingEmitter();
        RecordingEmitter healthy = new RecordingEmitter();
        state.subscribe(ROOM_A, null, broken);
        state.subscribe(ROOM_A, null, healthy);
        broken.failWithRuntimeException = true;
        ChatMessage message = messageIn(ROOM_A);

        state.deliver(message);

        List<String> expected = List.of(idOf(message));
        assertEquals(expected, healthy.receivedIds);
        assertTrue(broken.receivedIds.isEmpty());
    }

    /**
     * Anmelden mit Cursor und gleichzeitiges Verteilen: weder Lücke noch
     * Doppeltes (4.6). Ein Thread verteilt 200 Nachrichten, ein zweiter meldet
     * sich mittendrin mit dem Cursor an. 1000 Durchläufe, damit ein
     * Zeitfehler sich zeigt.
     */
    @Test
    void subscribingWhileMessagesArriveLosesNothingAndRepeatsNothing() throws Exception {
        for (int round = 0; round < 1000; round++) {
            LiveDeliveryState state = newState();
            ChatMessage cursor = messageIn(ROOM_A);
            state.deliver(cursor);
            List<ChatMessage> all = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                ChatMessage message = messageIn(ROOM_A);
                all.add(message);
            }
            RecordingEmitter emitter = new RecordingEmitter();

            Thread sender = new Thread(new Runnable() {
                /** Verteilt alle 200 Nachrichten nacheinander. */
                @Override
                public void run() {
                    for (ChatMessage message : all) {
                        state.deliver(message);
                    }
                }
            });
            Thread subscriber = new Thread(new Runnable() {
                /** Meldet sich irgendwann während des Verteilens an. */
                @Override
                public void run() {
                    UUID cursorId = cursor.id();
                    state.subscribe(ROOM_A, cursorId, emitter);
                }
            });
            sender.start();
            subscriber.start();
            sender.join();
            subscriber.join();

            List<String> expected = new ArrayList<>();
            for (ChatMessage message : all) {
                String id = idOf(message);
                expected.add(id);
            }
            assertEquals(expected, emitter.receivedIds, "Durchlauf " + round);
        }
    }
}
