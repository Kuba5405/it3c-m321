package ch.benedict.m321.batchwriter.service;

import ch.benedict.m321.batchwriter.IntegrationTestBase;
import ch.benedict.m321.batchwriter.dto.ChatMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Prüft den Kern des Dienstes gegen eine ECHTE Datenbank: ein Stapel geht in
 * einer Transaktion hinein, und Duplikate richten keinen Schaden an.
 */
class MessageRepositoryIntegrationTest extends IntegrationTestBase {

    @Autowired
    private MessageRepository messageRepository;

    /**
     * Der Normalfall: drei Nachrichten hinein, drei Zeilen heraus, mit
     * allen Feldern richtig.
     */
    @Test
    void storesAllMessagesOfABatch() {
        ChatMessage first = createMessage("eins");
        ChatMessage second = createMessage("zwei");
        ChatMessage third = createMessage("drei");
        List<ChatMessage> batch = List.of(first, second, third);

        int inserted = messageRepository.saveAll(batch);

        assertEquals(3, inserted);
        assertRowCount(3);
        assertStoredLikeSent(first);
    }

    /**
     * Szenario S5, erste Hälfte: dieselbe Nachricht kommt in zwei getrennten
     * Stapeln an, etwa weil der Broker sie nach einem Absturz erneut liefert.
     * Es darf nur eine Zeile geben, und der zweite Aufruf meldet 0 neue.
     */
    @Test
    void ignoresTheSameMessageInASecondBatch() {
        ChatMessage message = createMessage("nur einmal");
        List<ChatMessage> firstBatch = List.of(message);
        List<ChatMessage> secondBatch = List.of(message);

        int firstInserted = messageRepository.saveAll(firstBatch);
        int secondInserted = messageRepository.saveAll(secondBatch);

        assertEquals(1, firstInserted);
        assertEquals(0, secondInserted);
        assertRowCount(1);
    }

    /**
     * Szenario S5, zweite Hälfte: beide Kopien liegen im selben Stapel.
     * Das passiert, wenn sie kurz hintereinander ankommen.
     */
    @Test
    void ignoresTheSameMessageTwiceInOneBatch() {
        ChatMessage message = createMessage("zweimal im Stapel");
        List<ChatMessage> batch = List.of(message, message);

        int inserted = messageRepository.saveAll(batch);

        assertEquals(1, inserted);
        assertRowCount(1);
    }

    /**
     * Bei gleicher id gewinnt die ERSTE Nachricht. Eine spätere mit anderem
     * Text überschreibt sie nicht (Spezifikation 5, E5).
     */
    @Test
    void keepsTheFirstMessageWhenTheIdIsTakenAlready() {
        ChatMessage original = createMessage("Original");
        ChatMessage impostor = new ChatMessage(original.id(), original.roomId(),
                "mallory", "Mallory", "Fälschung", original.sentAt());
        List<ChatMessage> originalBatch = List.of(original);
        List<ChatMessage> impostorBatch = List.of(impostor);
        messageRepository.saveAll(originalBatch);

        messageRepository.saveAll(impostorBatch);

        assertStoredLikeSent(original);
    }

    /**
     * Ein leerer Stapel ist kein Fehler: der Dienst darf nie wegen "nichts zu tun" abstürzen.
     */
    @Test
    void acceptsAnEmptyBatch() {
        List<ChatMessage> emptyBatch = new ArrayList<>();

        int inserted = messageRepository.saveAll(emptyBatch);

        assertEquals(0, inserted);
    }

    /**
     * Der Kern von Szenario S4: 1000 Nachrichten kosten die Datenbank GENAU EINE
     * Schreib-Transaktion, nicht tausend.
     *
     * Gemessen mit txid_current(): nur Transaktionen, die schreiben, bekommen
     * eine Nummer, und der Aufruf selbst verbraucht eine. Liegen zwischen zwei
     * Aufrufen genau 2 Nummern, dann war dazwischen genau eine Schreib-Transaktion.
     */
    @Test
    void writesThousandMessagesInOneTransaction() {
        List<ChatMessage> batch = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            ChatMessage message = createMessage("Nachricht " + i);
            batch.add(message);
        }
        Long before = jdbcTemplate.queryForObject("SELECT txid_current()", Long.class);

        int inserted = messageRepository.saveAll(batch);

        Long after = jdbcTemplate.queryForObject("SELECT txid_current()", Long.class);
        assertEquals(1000, inserted);
        assertRowCount(1000);
        assertEquals(2, after - before, "Expected exactly one write transaction in between");
    }

    /**
     * Alles oder nichts: scheitert eine Zeile, ist KEINE des Stapels in der
     * Tabelle. Sonst würde die Wiederholung (Spezifikation 3.4) auf einem
     * halb geschriebenen Stapel aufsetzen.
     *
     * Der Fehler wird mit dem Zeichen NUL im Text erzwungen. Der Parser lässt
     * es nie durch, hier umgehen wir ihn absichtlich.
     */
    @Test
    void storesNothingWhenOneRowFails() {
        ChatMessage good = createMessage("gut");
        ChatMessage broken = createMessage("kaputt\u0000");
        List<ChatMessage> batch = List.of(good, broken);

        try {
            messageRepository.saveAll(batch);
            fail("The database should have rejected the NUL character");
        } catch (DataAccessException expected) {
            assertRowCount(0);
        }
    }

    /**
     * Die Datenbank speichert sechs Nachkommastellen, der chat-service schickt
     * neun (Spezifikation 2.2). Der Test hält fest, was wirklich passiert,
     * statt es anzunehmen.
     */
    @Test
    void storesTimestampWithSixDigits() {
        Instant sentAt = Instant.parse("2026-10-01T07:38:47.713518600Z");
        UUID messageId = UUID.randomUUID();
        UUID roomId = UUID.randomUUID();
        ChatMessage message = new ChatMessage(messageId, roomId, "anna", "Anna Muster", "Hallo", sentAt);
        List<ChatMessage> batch = List.of(message);

        messageRepository.saveAll(batch);

        OffsetDateTime stored = jdbcTemplate.queryForObject(
                "SELECT sent_at FROM message WHERE id = ?", OffsetDateTime.class, message.id());
        Instant storedInstant = stored.toInstant();
        Instant expectedInstant = Instant.parse("2026-10-01T07:38:47.713519Z");
        assertEquals(expectedInstant, storedInstant);
    }

    /**
     * Liest die Zeile mit der id der Nachricht zurück und vergleicht jedes Feld.
     */
    private void assertStoredLikeSent(ChatMessage sent) {
        String content = jdbcTemplate.queryForObject(
                "SELECT content FROM message WHERE id = ?", String.class, sent.id());
        String senderId = jdbcTemplate.queryForObject(
                "SELECT sender_id FROM message WHERE id = ?", String.class, sent.id());
        String senderName = jdbcTemplate.queryForObject(
                "SELECT sender_name FROM message WHERE id = ?", String.class, sent.id());
        UUID roomId = jdbcTemplate.queryForObject(
                "SELECT room_id FROM message WHERE id = ?", UUID.class, sent.id());
        OffsetDateTime sentAt = jdbcTemplate.queryForObject(
                "SELECT sent_at FROM message WHERE id = ?", OffsetDateTime.class, sent.id());

        assertEquals(sent.content(), content);
        assertEquals(sent.senderId(), senderId);
        assertEquals(sent.senderName(), senderName);
        assertEquals(sent.roomId(), roomId);
        assertEquals(sent.sentAt(), sentAt.toInstant());
    }
}
