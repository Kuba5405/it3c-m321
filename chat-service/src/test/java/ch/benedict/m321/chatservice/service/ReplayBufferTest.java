package ch.benedict.m321.chatservice.service;

import ch.benedict.m321.chatservice.dto.ChatMessage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prüft den Replay-Puffer ohne Spring und ohne Broker: reine Logik, darum
 * laufen diese Tests in Millisekunden (Spezifikation 4.6).
 */
class ReplayBufferTest {

    private static final UUID ROOM_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID ROOM_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    /** Baut eine Testnachricht, damit die Tests kurz und lesbar bleiben. */
    private ChatMessage messageIn(UUID roomId) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        return new ChatMessage(id, roomId, "anna", "Anna Muster", "Hallo", now);
    }

    /** Eine neue Nachricht wird gemeldet als neu und danach als bekannt. */
    @Test
    void addReportsNewMessageAsNewAndThenAsKnown() {
        ReplayBuffer buffer = new ReplayBuffer();
        ChatMessage message = messageIn(ROOM_A);

        boolean firstTime = buffer.add(message);
        boolean secondTime = buffer.add(message);

        assertTrue(firstTime);
        assertFalse(secondTime);
        assertTrue(buffer.contains(message.id()));
        assertEquals(1, buffer.size());
    }

    /** Beim 1001. Eintrag fliegt der älteste raus, die Grösse bleibt 1000. */
    @Test
    void dropsTheOldestMessageWhenTheThousandAndFirstArrives() {
        ReplayBuffer buffer = new ReplayBuffer();
        ChatMessage oldest = messageIn(ROOM_A);
        buffer.add(oldest);
        ChatMessage second = null;
        for (int i = 1; i < ReplayBuffer.CAPACITY; i++) {
            ChatMessage message = messageIn(ROOM_A);
            if (i == 1) {
                second = message;
            }
            buffer.add(message);
        }
        assertEquals(1000, buffer.size());
        assertTrue(buffer.contains(oldest.id()));

        buffer.add(messageIn(ROOM_A));

        assertEquals(1000, buffer.size());
        assertFalse(buffer.contains(oldest.id()));
        assertTrue(buffer.contains(second.id()));
    }

    /** Nach dem Cursor kommt nur, was später eintraf, in Reihenfolge, ohne den Cursor selbst. */
    @Test
    void messagesAfterReturnsOnlyLaterMessagesInOrder() {
        ReplayBuffer buffer = new ReplayBuffer();
        ChatMessage cursor = messageIn(ROOM_A);
        ChatMessage later1 = messageIn(ROOM_A);
        ChatMessage later2 = messageIn(ROOM_A);
        buffer.add(messageIn(ROOM_A));
        buffer.add(cursor);
        buffer.add(later1);
        buffer.add(later2);

        List<ChatMessage> result = buffer.messagesAfter(cursor.id(), ROOM_A);

        assertEquals(2, result.size());
        assertEquals(later1.id(), result.get(0).id());
        assertEquals(later2.id(), result.get(1).id());
    }

    /** Nachrichten anderer Räume gehören nicht in den Replay. */
    @Test
    void messagesAfterSkipsMessagesOfOtherRooms() {
        ReplayBuffer buffer = new ReplayBuffer();
        ChatMessage cursor = messageIn(ROOM_A);
        ChatMessage otherRoom = messageIn(ROOM_B);
        ChatMessage sameRoom = messageIn(ROOM_A);
        buffer.add(cursor);
        buffer.add(otherRoom);
        buffer.add(sameRoom);

        List<ChatMessage> result = buffer.messagesAfter(cursor.id(), ROOM_A);

        assertEquals(1, result.size());
        assertEquals(sameRoom.id(), result.get(0).id());
    }

    /** Ein Cursor aus dem Puffer und im richtigen Raum ist gültig. */
    @Test
    void knowsACursorInTheRequestedRoom() {
        ReplayBuffer buffer = new ReplayBuffer();
        ChatMessage cursor = messageIn(ROOM_A);
        buffer.add(cursor);

        boolean known = buffer.knowsCursor(cursor.id(), ROOM_A);

        assertTrue(known);
    }

    /** Unbekannt, verdrängt und raumfremd sind alle «nicht gefunden» (Grundlage für 409). */
    @Test
    void doesNotKnowUnknownEvictedOrForeignRoomCursors() {
        ReplayBuffer buffer = new ReplayBuffer();
        ChatMessage evicted = messageIn(ROOM_A);
        buffer.add(evicted);
        for (int i = 0; i < ReplayBuffer.CAPACITY; i++) {
            buffer.add(messageIn(ROOM_A));
        }
        ChatMessage inRoomA = messageIn(ROOM_A);
        buffer.add(inRoomA);

        boolean unknown = buffer.knowsCursor(UUID.randomUUID(), ROOM_A);
        boolean evictedKnown = buffer.knowsCursor(evicted.id(), ROOM_A);
        boolean foreignRoom = buffer.knowsCursor(inRoomA.id(), ROOM_B);

        assertFalse(unknown);
        assertFalse(evictedKnown);
        assertFalse(foreignRoom);
    }
}
