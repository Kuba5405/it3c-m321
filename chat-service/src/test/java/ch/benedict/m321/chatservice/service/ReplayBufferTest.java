package ch.benedict.m321.chatservice.service;

import ch.benedict.m321.chatservice.dto.ChatMessage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
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

    /** Baut eine Nachricht mit einem Inhalt der gewünschten Länge. */
    private ChatMessage messageWithContentLength(UUID roomId, int length) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        String content = "x".repeat(length);
        return new ChatMessage(id, roomId, "anna", "Anna Muster", content, now);
    }

    /**
     * Verpackt eine Nachricht so, wie sie im Puffer liegt. Das JSON spielt für den
     * Puffer keine Rolle, darum genügt ein Platzhalter.
     */
    private BufferedMessage buffered(ChatMessage message) {
        return new BufferedMessage(message, "{}");
    }

    /** Legt eine Nachricht in den Puffer und meldet, ob sie neu war. */
    private boolean add(ReplayBuffer buffer, ChatMessage message) {
        BufferedMessage buffered = buffered(message);
        return buffer.add(buffered);
    }

    /** Legt eine neue Nachricht in den Puffer und gibt sie zurück. */
    private ChatMessage addMessageIn(ReplayBuffer buffer, UUID roomId) {
        ChatMessage message = messageIn(roomId);
        add(buffer, message);
        return message;
    }

    /** Die Nachrichten aus dem Ergebnis eines Replays, ohne das JSON. */
    private List<ChatMessage> messagesOf(List<BufferedMessage> bufferedMessages) {
        List<ChatMessage> result = new ArrayList<>();
        for (BufferedMessage bufferedMessage : bufferedMessages) {
            ChatMessage message = bufferedMessage.message();
            result.add(message);
        }
        return result;
    }

    /** Die ID einer Nachricht, damit Aufrufe nicht verschachtelt werden müssen. */
    private UUID idOf(ChatMessage message) {
        return message.id();
    }

    /** Eine neue Nachricht wird gemeldet als neu und danach als bekannt. */
    @Test
    void addReportsNewMessageAsNewAndThenAsKnown() {
        ReplayBuffer buffer = new ReplayBuffer();
        ChatMessage message = messageIn(ROOM_A);
        UUID id = idOf(message);

        boolean firstTime = add(buffer, message);
        boolean secondTime = add(buffer, message);
        boolean contained = buffer.contains(id);
        int size = buffer.size();

        assertTrue(firstTime);
        assertFalse(secondTime);
        assertTrue(contained);
        assertEquals(1, size);
    }

    /** Beim 1001. Eintrag fliegt der älteste raus, die Grösse bleibt 1000. */
    @Test
    void dropsTheOldestMessageWhenTheThousandAndFirstArrives() {
        ReplayBuffer buffer = new ReplayBuffer();
        ChatMessage oldest = addMessageIn(buffer, ROOM_A);
        ChatMessage second = addMessageIn(buffer, ROOM_A);
        for (int i = 2; i < ReplayBuffer.CAPACITY; i++) {
            addMessageIn(buffer, ROOM_A);
        }
        UUID oldestId = idOf(oldest);
        UUID secondId = idOf(second);
        int sizeWhenFull = buffer.size();
        boolean oldestWasThere = buffer.contains(oldestId);

        addMessageIn(buffer, ROOM_A);

        int sizeAfter = buffer.size();
        boolean oldestStillThere = buffer.contains(oldestId);
        boolean secondStillThere = buffer.contains(secondId);
        assertEquals(1000, sizeWhenFull);
        assertTrue(oldestWasThere);
        assertEquals(1000, sizeAfter);
        assertFalse(oldestStillThere);
        assertTrue(secondStillThere);
    }

    /** Nach dem Cursor kommt nur, was später eintraf, in Reihenfolge, ohne den Cursor selbst. */
    @Test
    void messagesAfterReturnsOnlyLaterMessagesInOrder() {
        ReplayBuffer buffer = new ReplayBuffer();
        addMessageIn(buffer, ROOM_A);
        ChatMessage cursor = addMessageIn(buffer, ROOM_A);
        ChatMessage later1 = addMessageIn(buffer, ROOM_A);
        ChatMessage later2 = addMessageIn(buffer, ROOM_A);
        UUID cursorId = idOf(cursor);

        List<BufferedMessage> replayed = buffer.messagesAfter(cursorId, ROOM_A);

        List<ChatMessage> result = messagesOf(replayed);
        assertEquals(2, result.size());
        assertEquals(later1, result.get(0));
        assertEquals(later2, result.get(1));
    }

    /** Nachrichten anderer Räume gehören nicht in den Replay. */
    @Test
    void messagesAfterSkipsMessagesOfOtherRooms() {
        ReplayBuffer buffer = new ReplayBuffer();
        ChatMessage cursor = addMessageIn(buffer, ROOM_A);
        addMessageIn(buffer, ROOM_B);
        ChatMessage sameRoom = addMessageIn(buffer, ROOM_A);
        UUID cursorId = idOf(cursor);

        List<BufferedMessage> replayed = buffer.messagesAfter(cursorId, ROOM_A);

        List<ChatMessage> result = messagesOf(replayed);
        assertEquals(1, result.size());
        assertEquals(sameRoom, result.get(0));
    }

    /** Ein Cursor aus dem Puffer und im richtigen Raum ist gültig. */
    @Test
    void knowsACursorInTheRequestedRoom() {
        ReplayBuffer buffer = new ReplayBuffer();
        ChatMessage cursor = addMessageIn(buffer, ROOM_A);
        UUID cursorId = idOf(cursor);

        boolean known = buffer.knowsCursor(cursorId, ROOM_A);

        assertTrue(known);
    }

    /** Unbekannt, verdrängt und raumfremd sind alle «nicht gefunden» (Grundlage für 409). */
    @Test
    void doesNotKnowUnknownEvictedOrForeignRoomCursors() {
        ReplayBuffer buffer = new ReplayBuffer();
        ChatMessage evicted = addMessageIn(buffer, ROOM_A);
        for (int i = 0; i < ReplayBuffer.CAPACITY; i++) {
            addMessageIn(buffer, ROOM_A);
        }
        ChatMessage inRoomA = addMessageIn(buffer, ROOM_A);
        UUID unknownId = UUID.randomUUID();
        UUID evictedId = idOf(evicted);
        UUID inRoomAId = idOf(inRoomA);

        boolean unknown = buffer.knowsCursor(unknownId, ROOM_A);
        boolean evictedKnown = buffer.knowsCursor(evictedId, ROOM_A);
        boolean foreignRoom = buffer.knowsCursor(inRoomAId, ROOM_B);

        assertFalse(unknown);
        assertFalse(evictedKnown);
        assertFalse(foreignRoom);
    }

    /**
     * Der Puffer ist auch nach GRÖSSE begrenzt, nicht nur nach Anzahl: tausend
     * Nachrichten zu je 500 KB würden sonst etwa 500 MB Heap belegen (Review).
     * Drei Nachrichten zu je 8 Millionen Zeichen übersteigen die Grenze, die
     * älteste muss weichen.
     */
    @Test
    void dropsTheOldestMessagesWhenTheTotalSizeIsTooLarge() {
        ReplayBuffer buffer = new ReplayBuffer();
        ChatMessage oldest = messageWithContentLength(ROOM_A, 8_000_000);
        ChatMessage second = messageWithContentLength(ROOM_A, 8_000_000);
        ChatMessage newest = messageWithContentLength(ROOM_A, 8_000_000);

        add(buffer, oldest);
        add(buffer, second);
        add(buffer, newest);

        UUID oldestId = idOf(oldest);
        UUID newestId = idOf(newest);
        boolean oldestStillThere = buffer.contains(oldestId);
        boolean newestThere = buffer.contains(newestId);
        int size = buffer.size();
        assertFalse(oldestStillThere);
        assertTrue(newestThere);
        assertEquals(2, size);
    }

    /** Auch eine einzelne, zu grosse Nachricht bleibt als neueste im Puffer, sonst wäre ihr Cursor sofort ungültig. */
    @Test
    void keepsTheNewestMessageEvenWhenItAloneIsTooLarge() {
        ReplayBuffer buffer = new ReplayBuffer();
        ChatMessage huge = messageWithContentLength(ROOM_A, ReplayBuffer.MAX_TOTAL_CHARACTERS + 1000);

        add(buffer, huge);

        UUID hugeId = idOf(huge);
        boolean contained = buffer.contains(hugeId);
        assertTrue(contained);
    }

    /** Nach dem Verdrängen zählt die Grösse richtig: kleine Nachrichten passen danach wieder hinein. */
    @Test
    void smallMessagesFitAgainAfterBigOnesAreEvicted() {
        ReplayBuffer buffer = new ReplayBuffer();
        ChatMessage big1 = messageWithContentLength(ROOM_A, 15_000_000);
        ChatMessage big2 = messageWithContentLength(ROOM_A, 15_000_000);
        add(buffer, big1);
        add(buffer, big2);
        for (int i = 0; i < 10; i++) {
            addMessageIn(buffer, ROOM_A);
        }

        int size = buffer.size();

        assertEquals(11, size);
    }
}
