package ch.benedict.m321.batchwriter.service;

import ch.benedict.m321.batchwriter.IntegrationTestBase;
import ch.benedict.m321.batchwriter.config.QueueNames;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Prüft gegen einen ECHTEN RabbitMQ, dass eine ungültige Nachricht
 * unverändert in chat.dlq ankommt, mit einer Begründung dazu.
 */
class DeadLetterPublisherIntegrationTest extends IntegrationTestBase {

    @Autowired
    private DeadLetterPublisher deadLetterPublisher;

    /**
     * Körper und Eigenschaften bleiben, wie sie waren, und der Header
     * x-error-reason sagt, warum die Nachricht hier liegt. Wer die Dead-Letter-Queue
     * später durchsieht, will die ORIGINALE Nachricht sehen, nicht eine
     * umgebaute.
     */
    @Test
    void forwardsTheMessageUnchangedWithTheReason() {
        byte[] body = "das ist kein JSON".getBytes(StandardCharsets.UTF_8);
        MessageProperties properties = new MessageProperties();
        properties.setContentType("application/json");
        Message invalidMessage = new Message(body, properties);

        deadLetterPublisher.publish(invalidMessage, "not a readable chat message");

        Message deadLetter = rabbitTemplate.receive(QueueNames.DEAD_LETTER_QUEUE, 5000);
        assertNotNull(deadLetter);
        assertArrayEquals(body, deadLetter.getBody());
        assertEquals("application/json", deadLetter.getMessageProperties().getContentType());
        Object reason = deadLetter.getMessageProperties().getHeader("x-error-reason");
        assertEquals("not a readable chat message", reason);
    }

    /**
     * Die Nachricht landet NUR in chat.dlq und nicht zusätzlich in
     * chat.persist, wo der Verbraucher sie sofort wieder abholen und
     * so eine Endlosschleife erzeugen würde.
     */
    @Test
    void doesNotPutTheMessageBackOnThePersistQueue() {
        byte[] body = "kaputt".getBytes(StandardCharsets.UTF_8);
        Message invalidMessage = new Message(body, new MessageProperties());

        deadLetterPublisher.publish(invalidMessage, "test");

        Message deadLetter = rabbitTemplate.receive(QueueNames.DEAD_LETTER_QUEUE, 5000);
        assertNotNull(deadLetter);
        Message onPersistQueue = rabbitTemplate.receive(QueueNames.PERSIST_QUEUE, 500);
        assertNull(onPersistQueue);
    }
}
