package ch.benedict.m321.batchwriter.service;

import ch.benedict.m321.batchwriter.dto.ChatMessage;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Prüft, welche Nachrichten der batch-writer akzeptiert und welche er
 * als ungültig ablehnt (Spezifikation 3.2).
 *
 * Diese Tests brauchen weder Broker noch Datenbank. Sie sind die
 * schnellsten des Moduls und legen fest, was "gültig" heisst, bevor
 * irgendetwas gespeichert wird.
 */
class MessageParserTest {

    /**
     * Der Körper, den der echte chat-service am 01.10.2026 in die Queue gelegt hat,
     * gelesen mit der Management-Schnittstelle von RabbitMQ. Beachte die neun
     * Nachkommastellen bei sentAt: die Datenbank speichert nur sechs.
     */
    private static final String REAL_CHAT_SERVICE_BODY = """
            {"id":"46998f49-b5aa-457a-a489-cf729adb142a",\
            "roomId":"3f2b1c4e-0000-0000-0000-000000000001",\
            "senderId":"anna",\
            "senderName":"Anna Muster",\
            "content":"Hallo",\
            "sentAt":"2026-10-01T07:38:47.713518425Z"}""";

    private final MessageParser messageParser = new MessageParser();

    /**
     * Der Normalfall: eine Nachricht, wie sie der chat-service wirklich schickt,
     * wird vollständig und richtig gelesen.
     */
    @Test
    void readsTheRealMessageOfTheChatService() throws InvalidMessageException {
        byte[] body = REAL_CHAT_SERVICE_BODY.getBytes(StandardCharsets.UTF_8);

        ChatMessage message = messageParser.parse(body);

        UUID expectedId = UUID.fromString("46998f49-b5aa-457a-a489-cf729adb142a");
        UUID expectedRoomId = UUID.fromString("3f2b1c4e-0000-0000-0000-000000000001");
        Instant expectedSentAt = Instant.parse("2026-10-01T07:38:47.713518425Z");
        assertEquals(expectedId, message.id());
        assertEquals(expectedRoomId, message.roomId());
        assertEquals("anna", message.senderId());
        assertEquals("Anna Muster", message.senderName());
        assertEquals("Hallo", message.content());
        assertEquals(expectedSentAt, message.sentAt());
    }

    /**
     * Ein Sender darf ein Feld dazunehmen, ohne den Schreibweg zu brechen.
     */
    @Test
    void ignoresUnknownFields() throws InvalidMessageException {
        String json = REAL_CHAT_SERVICE_BODY.replace("\"content\":\"Hallo\"",
                "\"content\":\"Hallo\",\"somethingNew\":42");
        byte[] body = json.getBytes(StandardCharsets.UTF_8);

        ChatMessage message = messageParser.parse(body);

        assertEquals("Hallo", message.content());
    }

    /**
     * Text, der gar kein JSON ist, darf den Dienst nicht zum Absturz bringen.
     */
    @Test
    void rejectsBodyThatIsNotJson() {
        assertRejected("this is not json", "not a readable chat message");
    }

    /**
     * Ein leerer Körper ist kein Spezialfall, sondern einfach ungültig.
     */
    @Test
    void rejectsEmptyBody() {
        assertRejected("", "not a readable chat message");
    }

    /**
     * Das JSON-Wort null ist gültiges JSON, aber keine Nachricht.
     */
    @Test
    void rejectsJsonNull() {
        assertRejected("null", "JSON null");
    }

    /**
     * Fehlt irgendeines der sechs Felder, ist die Nachricht ungültig.
     * Wir probieren jedes einzeln und setzen dafür im echten Körper ein Feld
     * auf null. Für den Parser ist das dasselbe wie ein fehlendes Feld.
     */
    @Test
    void rejectsEveryMissingField() {
        String[] fieldNames = {"id", "roomId", "senderId", "senderName", "content", "sentAt"};

        for (int i = 0; i < fieldNames.length; i++) {
            String fieldName = fieldNames[i];
            String json = withFieldSetToNull(fieldName);
            assertRejected(json, "'" + fieldName + "' is missing");
        }
    }

    /**
     * Ein Text nur aus Leerzeichen ist keine Nachricht. Der chat-service
     * lehnt ihn schon ab, aber der Schreibweg verlässt sich nicht darauf.
     */
    @Test
    void rejectsBlankContent() {
        String json = REAL_CHAT_SERVICE_BODY.replace("\"content\":\"Hallo\"", "\"content\":\"   \"");

        assertRejected(json, "'content' must not be blank");
    }

    /**
     * Auch ein leerer Absender macht die Nachricht ungültig.
     */
    @Test
    void rejectsBlankSenderName() {
        String json = REAL_CHAT_SERVICE_BODY.replace("\"senderName\":\"Anna Muster\"", "\"senderName\":\"\"");

        assertRejected(json, "'senderName' must not be blank");
    }

    /**
     * Eine id, die keine UUID ist, kann nicht in die Spalte vom Typ uuid.
     */
    @Test
    void rejectsUnreadableUuid() {
        String json = REAL_CHAT_SERVICE_BODY.replace("46998f49-b5aa-457a-a489-cf729adb142a", "keine-uuid");

        assertRejected(json, "not a readable chat message");
    }

    /**
     * Ein Zeitstempel, der kein Zeitpunkt ist, kann nicht in sent_at.
     */
    @Test
    void rejectsUnreadableTimestamp() {
        String json = REAL_CHAT_SERVICE_BODY.replace("2026-10-01T07:38:47.713518425Z", "gestern");

        assertRejected(json, "not a readable chat message");
    }

    /**
     * Das Zeichen U+0000 kann PostgreSQL in einem Text nicht speichern. Es
     * würde den ganzen Stapel ablehnen, und mit ihm 499 gesunde Nachrichten.
     * Darum wird es VOR der Datenbank aussortiert.
     */
    @Test
    void rejectsNullCharacterInText() {
        String json = REAL_CHAT_SERVICE_BODY.replace("\"content\":\"Hallo\"", "\"content\":\"Hal\\u0000lo\"");

        assertRejected(json, "'content' contains the NUL character");
    }

    /**
     * Ein Zeitpunkt weit in der Zukunft, den PostgreSQL ablehnt (der Bereich endet im
     * Jahr 294276). Er würde den ganzen Stapel scheitern lassen, bei jeder Wiederholung
     * erneut: die Queue stünde still. Gefunden bei den Randfall-Versuchen am 01.10.2026.
     */
    @Test
    void rejectsATimeFarInTheFuture() {
        String json = REAL_CHAT_SERVICE_BODY.replace("2026-10-01T07:38:47.713518425Z", "+999999999-12-31T23:59:59Z");

        assertRejected(json, "'sentAt' is out of range");
    }

    /**
     * Ein Zeitpunkt weit in der Vergangenheit. Hier lehnt PostgreSQL NICHT ab, es speichert
     * stillschweigend "-infinity". Das ist schlimmer als ein Fehler, denn niemand merkt es.
     */
    @Test
    void rejectsATimeFarInThePast() {
        String json = REAL_CHAT_SERVICE_BODY.replace("2026-10-01T07:38:47.713518425Z", "-999999999-01-01T00:00:00Z");

        assertRejected(json, "'sentAt' is out of range");
    }

    /**
     * Die Grenzen selbst gelten noch: das Jahr 1 und das Ende des Jahres 9999 sind erlaubt,
     * eine Nanosekunde davor und danach nicht.
     */
    @Test
    void acceptsTheLimitsOfTheTimeRangeButNothingBeyond() throws InvalidMessageException {
        String earliest = REAL_CHAT_SERVICE_BODY.replace("2026-10-01T07:38:47.713518425Z", "0001-01-01T00:00:00Z");
        String latest = REAL_CHAT_SERVICE_BODY.replace("2026-10-01T07:38:47.713518425Z", "9999-12-31T23:59:59.999999999Z");
        String tooEarly = REAL_CHAT_SERVICE_BODY.replace("2026-10-01T07:38:47.713518425Z", "0000-12-31T23:59:59.999999999Z");
        String tooLate = REAL_CHAT_SERVICE_BODY.replace("2026-10-01T07:38:47.713518425Z", "+10000-01-01T00:00:00Z");

        byte[] earliestBody = earliest.getBytes(StandardCharsets.UTF_8);
        byte[] latestBody = latest.getBytes(StandardCharsets.UTF_8);
        Instant expectedEarliest = Instant.parse("0001-01-01T00:00:00Z");
        Instant expectedLatest = Instant.parse("9999-12-31T23:59:59.999999999Z");

        ChatMessage first = messageParser.parse(earliestBody);
        ChatMessage last = messageParser.parse(latestBody);

        assertEquals(expectedEarliest, first.sentAt());
        assertEquals(expectedLatest, last.sentAt());
        assertRejected(tooEarly, "'sentAt' is out of range");
        assertRejected(tooLate, "'sentAt' is out of range");
    }

    /**
     * Baut aus dem echten Körper einen, in dem ein Feld null ist. Das ist
     * für den Parser dasselbe wie ein fehlendes Feld.
     */
    private String withFieldSetToNull(String fieldName) {
        String quotedName = "\"" + fieldName + "\":";
        int start = REAL_CHAT_SERVICE_BODY.indexOf(quotedName);
        int valueStart = start + quotedName.length();
        int valueEnd = REAL_CHAT_SERVICE_BODY.indexOf("\"", valueStart + 1) + 1;
        String before = REAL_CHAT_SERVICE_BODY.substring(0, valueStart);
        String after = REAL_CHAT_SERVICE_BODY.substring(valueEnd);
        return before + "null" + after;
    }

    /**
     * Prüft, dass der Parser den Körper ablehnt, und dass die Begründung
     * den erwarteten Teil enthält. Die Begründung landet später im Header
     * x-error-reason der Dead-Letter-Queue: sie muss einem Menschen helfen.
     */
    private void assertRejected(String json, String expectedReasonPart) {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);

        try {
            messageParser.parse(body);
            fail("Message should have been rejected: " + json);
        } catch (InvalidMessageException exception) {
            String reason = exception.getMessage();
            assertTrue(reason.contains(expectedReasonPart),
                    "Reason was: " + reason);
        }
    }
}
