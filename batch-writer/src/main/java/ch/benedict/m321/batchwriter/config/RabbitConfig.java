package ch.benedict.m321.batchwriter.config;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Richtet alles ein, was der batch-writer am Broker braucht: die beiden
 * Queues und die Art, wie Nachrichten gesammelt werden.
 */
@Configuration
public class RabbitConfig {

    /**
     * Die Schreib-Queue. Der batch-writer legt sie selbst an, weil der
     * chat-service es erst beim ersten Senden tut (Spezifikation 2.4).
     *
     * Die Argumente müssen mit denen des chat-service IDENTISCH sein, sonst
     * lehnt RabbitMQ die zweite Deklaration ab. "durable" heisst: die Queue
     * überlebt einen Neustart des Brokers.
     */
    @Bean
    public Queue persistQueue() {
        return QueueBuilder.durable(QueueNames.PERSIST_QUEUE)
                .deadLetterExchange("")
                .deadLetterRoutingKey(QueueNames.DEAD_LETTER_QUEUE)
                .build();
    }

    /**
     * Das Abstellgleis für Nachrichten, die niemand verarbeiten konnte.
     */
    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(QueueNames.DEAD_LETTER_QUEUE).build();
    }

    /**
     * Sagt dem Verbraucher-Container, wie er Stapel sammeln soll: er hält
     * Nachrichten zurück, bis batchSize erreicht ist ODER timeoutMs vergangen
     * sind, und gibt dem Listener dann die ganze Liste auf einmal.
     *
     * Zwei Zeitgrenzen, und beide sind nötig:
     * - batchReceiveTimeout begrenzt das Sammeln EINES Stapels insgesamt.
     * - receiveTimeout begrenzt das Warten auf die EINZELNE Nachricht.
     * Mit receiveTimeout allein würde ein Stapel, in den alle 150 ms eine
     * Nachricht fällt, nie als zu langsam gelten und sich bis 500 füllen
     * (Spezifikation 5, E2).
     *
     * prefetch = batchSize: der Broker darf höchstens so viele unbestätigte
     * Nachrichten ausliefern, wie in einen Stapel passen.
     *
     * Ein einziger Verbraucher pro Instanz: skaliert wird mit weiteren
     * Instanzen, nicht mit Threads (Spezifikation 5, E10).
     *
     * Unsinnige Einstellungen verhindern den Start, siehe checkSettings.
     */
    @Bean
    public SimpleRabbitListenerContainerFactory batchContainerFactory(
            ConnectionFactory connectionFactory,
            @Value("${batch-writer.batch-size}") int batchSize,
            @Value("${batch-writer.batch-timeout-ms}") long timeoutMs) {
        checkSettings(batchSize, timeoutMs);

        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setBatchListener(true);
        factory.setConsumerBatchEnabled(true);
        factory.setBatchSize(batchSize);
        factory.setBatchReceiveTimeout(timeoutMs);
        factory.setReceiveTimeout(timeoutMs);
        factory.setPrefetchCount(batchSize);
        factory.setConcurrentConsumers(1);
        return factory;
    }

    /**
     * Lehnt Einstellungen ab, mit denen der Dienst zwar startet, aber schlecht läuft.
     *
     * Ein Zeitlimit von 0 hiesse "gar nicht warten": der Verbraucher fragte in einer
     * Endlosschleife nach Nachrichten und belegte im Leerlauf einen ganzen
     * Prozessorkern. Gemessen am 01.10.2026. Besser ein Fehler beim Start, der
     * die Variable beim Namen nennt, als ein Dienst, der unbemerkt heiss läuft.
     */
    private void checkSettings(int batchSize, long timeoutMs) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("BATCH_SIZE must be at least 1, but is " + batchSize);
        }
        if (timeoutMs < 1) {
            throw new IllegalArgumentException("BATCH_TIMEOUT_MS must be at least 1, but is " + timeoutMs);
        }
    }
}
