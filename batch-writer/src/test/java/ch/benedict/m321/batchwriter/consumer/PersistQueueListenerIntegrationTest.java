package ch.benedict.m321.batchwriter.consumer;

import ch.benedict.m321.batchwriter.IntegrationTestBase;
import ch.benedict.m321.batchwriter.config.QueueNames;
import ch.benedict.m321.batchwriter.dto.ChatMessage;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Das Zusammenspiel ganz durch, mit ECHTEM RabbitMQ und ECHTER Datenbank:
 * Nachricht in die Queue, Zeile in die Tabelle.
 *
 * Hier stehen die Tests zu den Szenarien S3, S4 und S5.
 */
class PersistQueueListenerIntegrationTest extends IntegrationTestBase {

    /**
     * Szenario S3 im Kleinen: eine Nachricht, genau wie sie der chat-service schickt,
     * landet in der Tabelle, und die Queue ist danach leer.
     *
     * Es ist zugleich der Test für das Zeitlimit: ein einzelne Nachricht füllt keinen
     * Stapel von 500, wird aber nach höchstens BATCH_TIMEOUT_MS trotzdem geschrieben.
     */
    @Test
    void writesASingleMessageWithoutWaitingForAFullBatch() throws Exception {
        ChatMessage message = createMessage("Hallo Schreibweg");

        publishLikeChatService(message);

        waitForRows(1);
        waitForQueueCount(QueueNames.PERSIST_QUEUE, 0);
        assertRowCount(1);
        assertQueueCount(QueueNames.PERSIST_QUEUE, 0);
        String content = jdbcTemplate.queryForObject(
                "SELECT content FROM message WHERE id = ?", String.class, message.id());
        assertEquals("Hallo Schreibweg", content);
    }

    /**
     * Szenario S5: dieselbe Nachricht zweimal, mit NUR dem Header content_type.
     * Der Verbraucher wird vorher angehalten, damit beide Kopien sicher im
     * selben Stapel ankommen. Erwartet: genau eine Zeile, nichts in chat.dlq.
     */
    @Test
    void storesADuplicateOnlyOnce() throws Exception {
        ChatMessage message = createMessage("zweimal geschickt");
        String json = toJson(message);
        stopListener();
        publishWithOnlyContentType(json);
        publishWithOnlyContentType(json);
        waitForQueueCount(QueueNames.PERSIST_QUEUE, 2);

        startListener();

        waitForRows(1);
        waitForQueueCount(QueueNames.PERSIST_QUEUE, 0);
        assertRowCount(1);
        assertQueueCount(QueueNames.DEAD_LETTER_QUEUE, 0);
    }

    /**
     * Szenario S5, andere Variante: die zweite Kopie kommt erst, nachdem die
     * erste längst gespeichert ist, also in einem späteren Stapel.
     * So sieht es aus, wenn der Broker eine Nachricht nach einem Absturz erneut liefert.
     */
    @Test
    void storesADuplicateOnlyOnceAcrossTwoBatches() throws Exception {
        ChatMessage message = createMessage("später nochmal");
        String json = toJson(message);
        publishWithOnlyContentType(json);
        waitForRows(1);
        waitForQueueCount(QueueNames.PERSIST_QUEUE, 0);

        publishWithOnlyContentType(json);

        Thread.sleep(1000);
        waitForQueueCount(QueueNames.PERSIST_QUEUE, 0);
        assertRowCount(1);
        assertQueueCount(QueueNames.DEAD_LETTER_QUEUE, 0);
    }

    /**
     * Eine kaputte Nachricht darf die gesunden im selben Stapel nicht aufhalten:
     * die beiden gültigen stehen in der Tabelle, die kaputte liegt mit
     * Begründung in chat.dlq.
     */
    @Test
    void movesAnInvalidMessageToTheDeadLetterQueueAndStoresTheRest() throws Exception {
        ChatMessage first = createMessage("gültig eins");
        ChatMessage second = createMessage("gültig zwei");
        stopListener();
        publishLikeChatService(first);
        publishWithOnlyContentType("das ist kein JSON");
        publishLikeChatService(second);
        waitForQueueCount(QueueNames.PERSIST_QUEUE, 3);

        startListener();

        waitForRows(2);
        waitForQueueCount(QueueNames.PERSIST_QUEUE, 0);
        waitForQueueCount(QueueNames.DEAD_LETTER_QUEUE, 1);
        assertRowCount(2);
        Message deadLetter = rabbitTemplate.receive(QueueNames.DEAD_LETTER_QUEUE, 5000);
        assertNotNull(deadLetter);
        MessageProperties deadLetterProperties = deadLetter.getMessageProperties();
        Object reason = deadLetterProperties.getHeader("x-error-reason");
        String reasonText = reason.toString();
        boolean namesTheProblem = reasonText.contains("not a readable chat message");
        assertTrue(namesTheProblem, reasonText);
    }

    /**
     * Szenario S4: der batch-writer war weg, 1000 Nachrichten haben sich in der Queue
     * gesammelt, jetzt kommt er zurück. Nichts darf verloren gehen, und die Datenbank
     * soll dafür nur wenige Transaktionen brauchen, nicht tausend.
     *
     * Erwartet sind 2 Stapel zu 500. Wir lassen bis zu 10 zu, denn ein Stapel
     * kann zufällig früher enden. Das Szenario erlaubt 100.
     */
    @Test
    void catchesUpOnABacklogWithFewTransactions() throws Exception {
        stopListener();
        for (int i = 0; i < 1000; i++) {
            ChatMessage message = createMessage("Rückstand " + i);
            publishLikeChatService(message);
        }
        waitForQueueCount(QueueNames.PERSIST_QUEUE, 1000);
        long before = currentTransactionNumber();

        startListener();

        waitForRows(1000);
        waitForQueueCount(QueueNames.PERSIST_QUEUE, 0);
        long after = currentTransactionNumber();
        long writeTransactions = after - before - 1;
        assertRowCount(1000);
        assertQueueCount(QueueNames.PERSIST_QUEUE, 0);
        assertTrue(writeTransactions <= 10, "Write transactions: " + writeTransactions);
    }

    /**
     * Die gefährlichste Nachricht: ein Zeitstempel im Jahr 999999999, den PostgreSQL
     * nicht speichern kann (der Bereich endet im Jahr 294276).
     *
     * Ohne Gegenmassnahme scheitert der ganze Stapel, kommt zurück, scheitert wieder,
     * und die Queue steht für immer still. Gefunden bei den Randfall-Versuchen am
     * 01.10.2026. Erwartet: die gesunden Nachrichten daneben werden gespeichert, die
     * unmögliche landet mit Begründung in chat.dlq.
     */
    @Test
    void movesAMessageWithAnImpossibleTimeToTheDeadLetterQueueAndStoresTheRest() throws Exception {
        ChatMessage first = createMessage("gesund eins");
        ChatMessage second = createMessage("gesund zwei");
        ChatMessage firstTemplate = createMessage("Jahr 999999999");
        Instant impossibleTime = Instant.parse("+999999999-12-31T23:59:59Z");
        ChatMessage impossible = new ChatMessage(firstTemplate.id(), firstTemplate.roomId(),
                "anna", "Anna Muster", "Jahr 999999999", impossibleTime);
        stopListener();
        publishLikeChatService(first);
        publishLikeChatService(impossible);
        publishLikeChatService(second);
        waitForQueueCount(QueueNames.PERSIST_QUEUE, 3);

        startListener();

        waitForRows(2);
        waitForQueueCount(QueueNames.PERSIST_QUEUE, 0);
        waitForQueueCount(QueueNames.DEAD_LETTER_QUEUE, 1);
        assertRowCount(2);
        assertQueueCount(QueueNames.PERSIST_QUEUE, 0);
        Message deadLetter = rabbitTemplate.receive(QueueNames.DEAD_LETTER_QUEUE, 5000);
        assertNotNull(deadLetter);
        MessageProperties deadLetterProperties = deadLetter.getMessageProperties();
        Object reason = deadLetterProperties.getHeader("x-error-reason");
        String reasonText = reason.toString();
        boolean namesTheProblem = reasonText.contains("out of range");
        assertTrue(namesTheProblem, reasonText);
    }

    /**
     * Mehrere unmögliche Zeitpunkte im selben Stapel, hier weit in der Vergangenheit, wo
     * PostgreSQL nicht ablehnt, sondern still "-infinity" speichert. Jede geht einzeln in
     * die Dead-Letter-Queue, die gesunden bleiben unberührt.
     */
    @Test
    void movesSeveralMessagesWithImpossibleTimesOfOneBatch() throws Exception {
        Instant impossibleTime = Instant.parse("-999999999-01-01T00:00:00Z");
        stopListener();
        for (int i = 0; i < 3; i++) {
            ChatMessage healthy = createMessage("gesund " + i);
            ChatMessage template = createMessage("kaputt " + i);
            ChatMessage impossible = new ChatMessage(template.id(), template.roomId(),
                    "anna", "Anna Muster", template.content(), impossibleTime);
            publishLikeChatService(healthy);
            publishLikeChatService(impossible);
        }
        waitForQueueCount(QueueNames.PERSIST_QUEUE, 6);

        startListener();

        waitForRows(3);
        waitForQueueCount(QueueNames.PERSIST_QUEUE, 0);
        waitForQueueCount(QueueNames.DEAD_LETTER_QUEUE, 3);
        assertRowCount(3);
        assertQueueCount(QueueNames.DEAD_LETTER_QUEUE, 3);
    }
}
