package ch.benedict.m321.batchwriter.service;

import ch.benedict.m321.batchwriter.config.QueueNames;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

/**
 * Legt Nachrichten, die wir nicht lesen können, in die Dead-Letter-Queue.
 *
 * Eine kaputte Nachricht darf die gesunden im selben Stapel nicht aufhalten.
 * Würde sie den Stapel scheitern lassen, käme sie bei jeder Wiederholung
 * wieder, und die Queue stünde still. Darum wandert sie ins Abstellgleis,
 * und der Stapel läuft weiter (Spezifikation 3.4).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DeadLetterPublisher {

    /** Der Standard-Exchange hat den Namen "": er leitet nach Queue-Namen weiter. */
    private static final String DEFAULT_EXCHANGE = "";

    /** Name des Headers, in dem die Begründung steht. */
    private static final String REASON_HEADER = "x-error-reason";

    private final RabbitTemplate rabbitTemplate;

    /**
     * Veröffentlicht die Nachricht unverändert in chat.dlq und hängt die
     * Begründung als Header an.
     *
     * Wer die Dead-Letter-Queue später durchsieht, will das Original sehen.
     * Darum bleiben Körper und alle übrigen Eigenschaften, wie sie waren.
     */
    public void publish(Message invalidMessage, String reason) {
        invalidMessage.getMessageProperties().setHeader(REASON_HEADER, reason);

        rabbitTemplate.send(DEFAULT_EXCHANGE, QueueNames.DEAD_LETTER_QUEUE, invalidMessage);

        log.warn("Invalid message moved to {}: {}", QueueNames.DEAD_LETTER_QUEUE, reason);
    }
}
