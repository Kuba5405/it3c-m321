package ch.benedict.m321.chatservice.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.env.Environment;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prüft gegen einen ECHTEN RabbitMQ, dass unsere Queues beim Start
 * tatsächlich angelegt werden. Ein Mock würde hier nichts beweisen:
 * die Deklaration passiert im Broker, nicht in unserem Code.
 */
@SpringBootTest
@Testcontainers
class RabbitConfigIntegrationTest {

    /**
     * ServiceConnection setzt spring.rabbitmq.host und -port automatisch
     * auf den gestarteten Container. Wir müssen nichts konfigurieren.
     */
    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private ConnectionFactory connectionFactory;

    @Autowired
    private Environment environment;

    /** Präfix, an dem wir die Instanzqueue unter allen Queues des Brokers erkennen. */
    private static final String LIVE_QUEUE_PREFIX = "chat.live.";

    @Test
    void declaresPersistQueue() {
        Properties properties = rabbitAdmin.getQueueProperties(QueueNames.PERSIST_QUEUE);

        assertNotNull(properties);
    }

    @Test
    void declaresDeadLetterQueue() {
        Properties properties = rabbitAdmin.getQueueProperties(QueueNames.DEAD_LETTER_QUEUE);

        assertNotNull(properties);
    }

    /**
     * Spring deklariert Queues erst, wenn die erste Verbindung zum Broker
     * entsteht. Heute öffnet beim Start noch nichts eine Verbindung; erst der
     * Listener in Schritt 4 tut das von selbst. Bis dahin holt initialize()
     * die Deklaration nach, damit die Tests die Queue sehen.
     */
    @BeforeEach
    void declareEverythingNow() {
        rabbitAdmin.initialize();
    }

    /**
     * Fragt den Broker selbst, welche Queues es gibt. Nur er weiss, ob eine
     * Queue exklusiv oder auto-delete ist; Spring zeigt uns das nicht an.
     * "-q" unterdrückt die Kopfzeile, die Spalten sind durch Tabulator getrennt.
     */
    private String listQueuesFromBroker() throws IOException, InterruptedException {
        var result = rabbitMq.execInContainer("rabbitmqctl", "-q", "list_queues",
                "name", "durable", "exclusive", "auto_delete", "arguments");
        return result.getStdout();
    }

    /** Sucht in der Ausgabe von rabbitmqctl die Zeile der Instanzqueue. */
    private String findLiveQueueLine() throws IOException, InterruptedException {
        String allQueues = listQueuesFromBroker();
        String[] lines = allQueues.split("\n");
        for (String line : lines) {
            if (line.startsWith(LIVE_QUEUE_PREFIX)) {
                return line;
            }
        }
        return null;
    }

    /** Der Name steht in der ersten Spalte der Zeile. */
    private String queueNameOf(String line) {
        String[] columns = line.split("\t");
        return columns[0];
    }

    /**
     * Die Instanzqueue gibt es nach dem Start, ohne dass je eine Nachricht
     * gesendet wurde (Spezifikation 3 und S2).
     */
    @Test
    void declaresLiveQueueAtStartup() throws Exception {
        String line = findLiveQueueLine();

        assertNotNull(line, "Keine Queue mit Präfix " + LIVE_QUEUE_PREFIX);
    }

    /**
     * Nicht dauerhaft, exklusiv, auto-delete: so räumt der Broker die Queue
     * von selbst auf, wenn die Instanz weg ist (S4).
     */
    @Test
    void liveQueueIsTransientExclusiveAndAutoDelete() throws Exception {
        String line = findLiveQueueLine();
        assertNotNull(line);

        String[] columns = line.split("\t");
        assertEquals("false", columns[1], "durable");
        assertEquals("true", columns[2], "exclusive");
        assertEquals("true", columns[3], "auto_delete");
    }

    /**
     * 750 bereite Nachrichten und drop-head: so bleibt Queue plus unbestätigte
     * Nachrichten unter 1000 (Spezifikation 3, S6).
     */
    @Test
    void liveQueueIsLimitedTo750MessagesAndDropsTheOldest() throws Exception {
        String line = findLiveQueueLine();
        assertNotNull(line);

        assertTrue(line.contains("x-max-length"), line);
        assertTrue(line.contains("750"), line);
        assertTrue(line.contains("drop-head"), line);
    }

    /** Die Queue hängt am Exchange, sonst bekäme sie nie eine Kopie. */
    @Test
    void liveQueueIsBoundToTheDeliveryExchange() throws Exception {
        String line = findLiveQueueLine();
        assertNotNull(line);
        String queueName = queueNameOf(line);

        var result = rabbitMq.execInContainer("rabbitmqctl", "-q", "list_bindings",
                "source_name", "destination_name", "destination_kind");
        String bindings = result.getStdout();

        String expected = QueueNames.DELIVERY_EXCHANGE + "\t" + queueName + "\tqueue";
        assertTrue(bindings.contains(expected), bindings);
    }

    /** Eine am Exchange veröffentlichte Nachricht muss in der Instanzqueue landen. */
    @Test
    void messageSentToTheExchangeArrivesInTheLiveQueue() throws Exception {
        String line = findLiveQueueLine();
        assertNotNull(line);
        String queueName = queueNameOf(line);

        rabbitTemplate.convertAndSend(QueueNames.DELIVERY_EXCHANGE, "", "hallo");

        int messageCount = waitForMessageCount(queueName, 1);
        assertEquals(1, messageCount);
    }

    /**
     * Das Senden ist asynchron: convertAndSend kehrt zurück, bevor der Broker
     * die Nachricht eingereiht hat. Darum fragen wir bis zu 3 Sekunden nach,
     * statt einmal zu früh.
     */
    private int waitForMessageCount(String queueName, int expected) throws InterruptedException {
        int messageCount = 0;
        for (int attempt = 0; attempt < 60; attempt++) {
            Properties properties = rabbitAdmin.getQueueProperties(queueName);
            Object countAsObject = properties.get(RabbitAdmin.QUEUE_MESSAGE_COUNT);
            messageCount = (Integer) countAsObject;
            if (messageCount >= expected) {
                return messageCount;
            }
            Thread.sleep(50);
        }
        return messageCount;
    }

    /**
     * Heartbeat 5 s, damit der Broker eine abgestürzte Instanz binnen Sekunden
     * erkennt und ihre Queue löscht (Spezifikation 3, S4).
     */
    @Test
    void requestsAHeartbeatOfFiveSeconds() {
        CachingConnectionFactory cachingFactory = (CachingConnectionFactory) connectionFactory;
        var rabbitFactory = cachingFactory.getRabbitConnectionFactory();
        int heartbeatSeconds = rabbitFactory.getRequestedHeartbeat();

        assertEquals(5, heartbeatSeconds);
    }

    /**
     * Die Vorabholmenge ist ausdrücklich 250 und nicht der Bibliothek überlassen.
     * 750 + 250 ergibt höchstens 1000 Nachrichten in der Queue (Spezifikation 3).
     */
    @Test
    void setsPrefetchExplicitlyTo250() {
        String prefetch = environment.getProperty("spring.rabbitmq.listener.simple.prefetch");

        assertEquals("250", prefetch);
    }
}
