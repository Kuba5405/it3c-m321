package ch.benedict.m321.batchwriter.config;

import ch.benedict.m321.batchwriter.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.containers.Container.ExecResult;

import java.io.IOException;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prüft gegen einen ECHTEN RabbitMQ, dass der batch-writer seine Queues
 * selbst anlegt, und zwar so, wie der chat-service sie anlegt.
 *
 * Das ist wichtig, weil der chat-service seine Queues erst beim ersten
 * Senden deklariert (Spezifikation 2.4). Wartet der batch-writer darauf,
 * kann er beim Start an einer Queue scheitern, die es noch nicht gibt.
 */
class RabbitConfigIntegrationTest extends IntegrationTestBase {

    @Autowired
    private RabbitAdmin rabbitAdmin;

    /**
     * Die Schreib-Queue existiert, ohne dass jemand vorher etwas gesendet hat.
     */
    @Test
    void declaresPersistQueue() {
        Properties properties = rabbitAdmin.getQueueProperties(QueueNames.PERSIST_QUEUE);

        assertNotNull(properties);
    }

    /**
     * Die Dead-Letter-Queue existiert ebenfalls, denn unser Dienst legt
     * ungültige Nachrichten dort ab.
     */
    @Test
    void declaresDeadLetterQueue() {
        Properties properties = rabbitAdmin.getQueueProperties(QueueNames.DEAD_LETTER_QUEUE);

        assertNotNull(properties);
    }

    /**
     * chat.persist trägt dieselben Argumente wie beim chat-service. Stimmen sie
     * nicht überein, lehnt RabbitMQ die zweite Deklaration ab, und der batch-writer
     * hängt ohne Verbraucher da (gemessen, Spezifikation 3.1). Gelesen wird so, wie
     * es auch ein Mensch nachprüfen würde: mit rabbitmqctl im Container.
     */
    @Test
    void persistQueueHasTheSameDeadLetterArgumentsAsTheChatService() throws IOException, InterruptedException {
        rabbitAdmin.getQueueProperties(QueueNames.PERSIST_QUEUE);

        ExecResult result = RABBIT.execInContainer("rabbitmqctl", "list_queues", "name", "arguments");
        String output = result.getStdout();

        boolean hasExchange = output.contains("x-dead-letter-exchange");
        boolean hasRoutingKey = output.contains("x-dead-letter-routing-key");
        boolean pointsToDeadLetterQueue = output.contains("chat.dlq");
        assertTrue(hasExchange, output);
        assertTrue(hasRoutingKey, output);
        assertTrue(pointsToDeadLetterQueue, output);
    }
}
