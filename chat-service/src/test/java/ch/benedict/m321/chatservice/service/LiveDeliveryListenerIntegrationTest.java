package ch.benedict.m321.chatservice.service;

import ch.benedict.m321.chatservice.config.QueueNames;
import ch.benedict.m321.chatservice.dto.ChatMessage;
import ch.benedict.m321.chatservice.service.LiveDeliveryStateTest.RecordingEmitter;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prüft gegen einen ECHTEN RabbitMQ, dass der Listener die Instanzqueue
 * liest und an LiveDeliveryState weitergibt (Spezifikation 3, 4.1, 4.7).
 * Ein Mock würde nichts beweisen: Zustellung, Bestätigung und Consumer-Zahl
 * entscheidet der Broker.
 */
@SpringBootTest
@Testcontainers
class LiveDeliveryListenerIntegrationTest {

    /** Setzt host und port der Anwendung automatisch auf den Container. */
    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private LiveDeliveryState state;

    @Autowired
    @Qualifier("liveQueue")
    private Queue liveQueue;

    /** Baut eine Testnachricht in einem eigenen Raum, damit Tests sich nicht stören. */
    private ChatMessage messageIn(UUID roomId, String senderId) {
        UUID id = UUID.randomUUID();
        return new ChatMessage(id, roomId, senderId, "Anna Muster", "Hallo", Instant.now());
    }

    /** Die ID einer Nachricht als Text, so wie sie im Ereignis (id:) steht. */
    private String idOf(ChatMessage message) {
        UUID id = message.id();
        return id.toString();
    }

    /** Veröffentlicht wie der chat-service am Fanout-Exchange. */
    private void publish(ChatMessage message) {
        rabbitTemplate.convertAndSend(QueueNames.DELIVERY_EXCHANGE, "", message);
    }

    /** Wartet bis zu 5 Sekunden, bis der Emitter so viele Ereignisse hat. */
    private void waitForEvents(RecordingEmitter emitter, int expected) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (emitter.receivedIds.size() >= expected) {
                return;
            }
            Thread.sleep(50);
        }
    }

    /** Fragt den Broker nach der Zahl der Nachrichten (bereit plus unbestätigt) der Instanzqueue. */
    private String brokerLineOfLiveQueue(String column) throws Exception {
        var result = rabbitMq.execInContainer("rabbitmqctl", "-q", "list_queues", "name", column);
        String[] lines = result.getStdout().split("\\n");
        for (String line : lines) {
            if (line.startsWith(liveQueue.getName())) {
                String[] columns = line.split("\\t");
                return columns[1].trim();
            }
        }
        return null;
    }

    /** Eine am Exchange veröffentlichte Nachricht kommt bei der Verbindung an. */
    @Test
    void messagePublishedToTheExchangeReachesTheConnection() throws Exception {
        UUID room = UUID.randomUUID();
        RecordingEmitter emitter = new RecordingEmitter();
        state.subscribe(room, null, emitter);
        ChatMessage message = messageIn(room, "anna");

        publish(message);
        waitForEvents(emitter, 1);

        List<String> expected = List.of(idOf(message));
        assertEquals(expected, emitter.receivedIds);
    }

    /**
     * Dieselbe UUID zweimal ergibt EIN Ereignis. Eine Kontrollnachricht
     * dahinter zeigt, dass der Listener nicht einfach noch nicht fertig ist.
     */
    @Test
    void sameMessageDeliveredTwiceProducesOneEvent() throws Exception {
        UUID room = UUID.randomUUID();
        RecordingEmitter emitter = new RecordingEmitter();
        state.subscribe(room, null, emitter);
        ChatMessage message = messageIn(room, "anna");
        ChatMessage control = messageIn(room, "anna");

        publish(message);
        publish(message);
        publish(control);
        waitForEvents(emitter, 2);

        List<String> expected = List.of(idOf(message), idOf(control));
        assertEquals(expected, emitter.receivedIds);
    }

    /** Zwei Nachrichten desselben Absenders bleiben in der veröffentlichten Reihenfolge. */
    @Test
    void keepsTheOrderOfOneSender() throws Exception {
        UUID room = UUID.randomUUID();
        RecordingEmitter emitter = new RecordingEmitter();
        state.subscribe(room, null, emitter);
        List<String> expected = new java.util.ArrayList<>();

        for (int i = 0; i < 50; i++) {
            ChatMessage message = messageIn(room, "anna");
            String id = idOf(message);
            expected.add(id);
            publish(message);
        }
        waitForEvents(emitter, 50);

        assertEquals(expected, emitter.receivedIds);
    }

    /**
     * Die Instanzqueue hat genau EINEN Consumer, und sie gibt es nach dem
     * Start, ohne dass je eine Nachricht gesendet wurde (S2, Spezifikation 3).
     */
    @Test
    void liveQueueHasExactlyOneConsumer() throws Exception {
        String consumers = brokerLineOfLiveQueue("consumers");

        assertEquals("1", consumers);
    }

    /**
     * Wirft eine Verbindung beim Senden, bleibt die Queue leer: der Listener
     * bestätigt trotzdem, ein Clientfehler ist kein Grund für Requeue (4.7).
     */
    @Test
    void aFailingClientDoesNotCauseRequeue() throws Exception {
        UUID room = UUID.randomUUID();
        RecordingEmitter broken = new RecordingEmitter();
        broken.failOnSend = true;
        RecordingEmitter healthy = new RecordingEmitter();
        state.subscribe(room, null, broken);
        state.subscribe(room, null, healthy);

        ChatMessage message = messageIn(room, "anna");
        publish(message);
        waitForEvents(healthy, 1);
        String messagesInQueue = "?";
        for (int attempt = 0; attempt < 60; attempt++) {
            messagesInQueue = brokerLineOfLiveQueue("messages");
            if ("0".equals(messagesInQueue)) {
                break;
            }
            Thread.sleep(50);
        }

        assertEquals(1, healthy.receivedIds.size());
        assertTrue(broken.receivedIds.isEmpty());
        assertEquals("0", messagesInQueue);
    }
}
