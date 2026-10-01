package ch.benedict.m321.batchwriter.consumer;

import ch.benedict.m321.batchwriter.config.QueueNames;
import ch.benedict.m321.batchwriter.dto.ChatMessage;
import ch.benedict.m321.batchwriter.service.DeadLetterPublisher;
import ch.benedict.m321.batchwriter.service.InvalidMessageException;
import ch.benedict.m321.batchwriter.service.MessageParser;
import ch.benedict.m321.batchwriter.service.MessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Der Eingang des Dienstes: bekommt Stapel aus der Queue chat.persist und
 * sorgt dafür, dass sie in der Datenbank landen.
 *
 * Diese Klasse enthält selbst keine Regeln. Sie ruft der Reihe nach auf,
 * was die anderen Klassen können: lesen, schreiben, weglegen. Den
 * Ablauf kann man hier von oben nach unten vorlesen (Spezifikation 3.2).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class PersistQueueListener {

    /**
     * Eine Nachricht, die wir nicht lesen konnten, samt Grund. Nur für den
     * Weg vom Prüfen zum Weglegen innerhalb dieser Klasse.
     */
    private record RejectedMessage(Message message, String reason) {
    }

    private final MessageParser messageParser;
    private final MessageRepository messageRepository;
    private final DeadLetterPublisher deadLetterPublisher;

    /**
     * Wird vom Container mit einem ganzen Stapel aufgerufen: höchstens
     * batchSize Nachrichten, oder was in der Zeit zusammenkam.
     *
     * Endet die Methode normal, bestätigt der Container den ganzen Stapel (ACK).
     * Wirft sie eine Exception, legt er den ganzen Stapel zurück in die Queue.
     * Darum steht das Bestätigen hier nirgends: der Weg ist "alles oder nichts".
     */
    @RabbitListener(queues = QueueNames.PERSIST_QUEUE, containerFactory = "batchContainerFactory")
    public void onBatch(List<Message> messages) {
        long startNanos = System.nanoTime();
        List<ChatMessage> validMessages = new ArrayList<>();
        List<RejectedMessage> rejectedMessages = new ArrayList<>();

        sortIntoValidAndRejected(messages, validMessages, rejectedMessages);

        int inserted = messageRepository.saveAll(validMessages);
        moveRejectedToDeadLetterQueue(rejectedMessages);

        logBatch(messages.size(), inserted, validMessages.size(), rejectedMessages.size(), startNanos);
    }

    /**
     * Liest jede Nachricht des Stapels und sortiert sie in zwei Listen:
     * die gültigen kommen in die Datenbank, die ungültigen ins Abstellgleis.
     */
    private void sortIntoValidAndRejected(List<Message> messages,
                                          List<ChatMessage> validMessages,
                                          List<RejectedMessage> rejectedMessages) {
        for (Message message : messages) {
            byte[] body = message.getBody();
            try {
                ChatMessage chatMessage = messageParser.parse(body);
                validMessages.add(chatMessage);
            } catch (InvalidMessageException exception) {
                RejectedMessage rejected = new RejectedMessage(message, exception.getMessage());
                rejectedMessages.add(rejected);
            }
        }
    }

    /**
     * Legt die ungültigen Nachrichten in chat.dlq. Das geschieht ERST NACH
     * dem Schreiben in die Datenbank: scheitert die Datenbank, wird der
     * Stapel wiederholt, und die kaputten Nachrichten stünden sonst bei jeder
     * Wiederholung erneut in der Dead-Letter-Queue (Spezifikation 3.2).
     */
    private void moveRejectedToDeadLetterQueue(List<RejectedMessage> rejectedMessages) {
        for (RejectedMessage rejected : rejectedMessages) {
            deadLetterPublisher.publish(rejected.message(), rejected.reason());
        }
    }

    /**
     * Schreibt eine Zeile pro Stapel ins Log: wie viele kamen, wie viele neu
     * in die Tabelle, wie viele waren Duplikate, wie viele ungültig, und wie
     * lange es dauerte.
     */
    private void logBatch(int received, int inserted, int valid, int rejected, long startNanos) {
        long durationNanos = System.nanoTime() - startNanos;
        long durationMillis = durationNanos / 1_000_000;
        int duplicates = valid - inserted;

        log.info("Batch done: {} received, {} inserted, {} duplicates, {} invalid, {} ms",
                received, inserted, duplicates, rejected, durationMillis);
    }
}
