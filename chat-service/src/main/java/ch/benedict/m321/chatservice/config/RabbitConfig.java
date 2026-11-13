package ch.benedict.m321.chatservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;

/**
 * Legt beim Start alles an, was der Broker braucht.
 *
 * Spring meldet diese Beans beim Verbindungsaufbau an RabbitMQ. Existiert
 * eine Queue schon, passiert nichts — das Anlegen ist wiederholbar.
 */
@Configuration
public class RabbitConfig {

    /** Präfix der Instanzqueue; dahinter steht eine UUID pro Prozess. */
    static final String LIVE_QUEUE_PREFIX = "chat.live.";

    /** Höchstzahl bereiter Nachrichten in der Instanzqueue (Spezifikation 3). */
    private static final int LIVE_QUEUE_MAX_LENGTH = 750;

    /**
     * Der Schreibweg. "durable" heisst: die Queue überlebt einen Neustart
     * des Brokers. Was der Verbraucher endgültig ablehnt, landet über den
     * Standard-Exchange ("") in der Dead-Letter-Queue.
     */
    @Bean
    public Queue persistQueue() {
        return QueueBuilder.durable(QueueNames.PERSIST_QUEUE)
                .deadLetterExchange("")
                .deadLetterRoutingKey(QueueNames.DEAD_LETTER_QUEUE)
                .build();
    }

    /** Das Abstellgleis für Nachrichten, die niemand verarbeiten konnte. */
    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(QueueNames.DEAD_LETTER_QUEUE).build();
    }

    /**
     * Der Zustellweg. Fanout heisst: JEDE gebundene Queue bekommt eine
     * Kopie. Das ist genau richtig, weil jede web-gateway-Instanz jede
     * Nachricht sehen muss — nur sie weiss, welche ihrer WebSocket-Clients
     * im betroffenen Raum sitzen.
     */
    @Bean
    public FanoutExchange deliveryExchange() {
        return new FanoutExchange(QueueNames.DELIVERY_EXCHANGE, true, false);
    }

    /**
     * Die eigene Queue dieser Instanz für die Live-Zustellung.
     *
     * Jede Instanz braucht eine EIGENE Queue: nur sie kennt ihre SSE-Clients,
     * und der Fanout-Exchange gibt jeder gebundenen Queue eine Kopie. Mit einer
     * gemeinsamen Queue bekäme nur eine Instanz die Nachricht.
     *
     * - nicht dauerhaft: nach einem Neustart soll nichts Altes zurückkommen
     * - exklusiv: die Queue gehört der Verbindung dieser Instanz
     * - auto-delete: der Broker löscht sie, wenn der letzte Consumer geht
     * - höchstens 750 bereite Nachrichten, mit "drop-head" fliegt die älteste raus.
     *   Dazu kommen höchstens 250 unbestätigte (Vorabholmenge, application.yml):
     *   zusammen nie mehr als 1000.
     *
     * Der Name entsteht bei jedem Start neu und ist darum keine Konstante.
     */
    @Bean
    public Queue liveQueue() {
        String uniqueName = LIVE_QUEUE_PREFIX + UUID.randomUUID();
        return QueueBuilder.nonDurable(uniqueName)
                .exclusive()
                .autoDelete()
                .maxLength(LIVE_QUEUE_MAX_LENGTH)
                .overflow(QueueBuilder.Overflow.dropHead)
                .build();
    }

    /** Hängt die Instanzqueue an den Zustellweg, sonst bekäme sie nie eine Kopie. */
    @Bean
    public Binding liveQueueBinding() {
        Queue queue = liveQueue();
        FanoutExchange exchange = deliveryExchange();
        return BindingBuilder.bind(queue).to(exchange);
    }

    /**
     * Nachrichten gehen als JSON über die Leitung, nicht als serialisiertes
     * Java-Objekt. Nur so kann später ein Dienst in einer anderen Sprache
     * mitlesen.
     *
     * Der ObjectMapper kommt von Spring Boot und kann bereits Instant im
     * ISO-8601-Format schreiben.
     */
    @Bean
    public MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }
}
