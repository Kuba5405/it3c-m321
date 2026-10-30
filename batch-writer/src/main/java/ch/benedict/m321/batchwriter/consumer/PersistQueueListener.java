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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Der Eingang des Dienstes: bekommt Stapel aus der Queue chat.persist und
 * sorgt dafür, dass sie in der Datenbank landen.
 *
 * Diese Klasse entscheidet nicht, was gültig ist. Sie ruft der Reihe nach auf,
 * was die anderen Klassen können: lesen, weglegen, schreiben. Den
 * Ablauf kann man hier von oben nach unten vorlesen (Spezifikation 3.2).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class PersistQueueListener {

    /**
     * Eine Nachricht, die wir nicht speichern können, samt Grund. Nur für den
     * Weg vom Prüfen zum Weglegen innerhalb dieser Klasse.
     */
    private record RejectedMessage(Message message, String reason) {
    }

    /**
     * Eine gültige Nachricht samt ihrem Original aus der Queue. Das Original
     * brauchen wir, falls die Datenbank die Nachricht doch ablehnt: in die
     * Dead-Letter-Queue gehört das Original, nicht unsere gelesene Fassung.
     */
    private record ValidMessage(Message message, ChatMessage chatMessage) {
    }

    private final MessageParser messageParser;
    private final MessageRepository messageRepository;
    private final DeadLetterPublisher deadLetterPublisher;

    /**
     * Wie lange wir nach einem Datenbankfehler warten, bevor der Stapel
     * zurückgelegt wird. Als Feld mit @Value statt im Konstruktor, weil
     * Lombok den Konstruktor nur für die drei final-Felder oben schreibt.
     */
    @Value("${batch-writer.retry-delay-ms}")
    private long retryDelayMillis;

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
        List<ValidMessage> validMessages = new ArrayList<>();
        List<RejectedMessage> rejectedMessages = new ArrayList<>();
        List<RejectedMessage> refusedMessages = new ArrayList<>();

        sortIntoValidAndRejected(messages, validMessages, rejectedMessages);
        moveRejectedToDeadLetterQueue(rejectedMessages);

        int inserted = writeToDatabase(validMessages, refusedMessages);
        moveRejectedToDeadLetterQueue(refusedMessages);

        logBatch(messages.size(), inserted, validMessages.size(), rejectedMessages.size(),
                refusedMessages.size(), startNanos);
    }

    /**
     * Liest jede Nachricht des Stapels und sortiert sie in zwei Listen:
     * die gültigen kommen in die Datenbank, die ungültigen ins Abstellgleis.
     */
    private void sortIntoValidAndRejected(List<Message> messages,
                                          List<ValidMessage> validMessages,
                                          List<RejectedMessage> rejectedMessages) {
        for (Message message : messages) {
            byte[] body = message.getBody();
            try {
                ChatMessage chatMessage = messageParser.parse(body);
                ValidMessage valid = new ValidMessage(message, chatMessage);
                validMessages.add(valid);
            } catch (InvalidMessageException exception) {
                RejectedMessage rejected = new RejectedMessage(message, exception.getMessage());
                rejectedMessages.add(rejected);
            }
        }
    }

    /**
     * Schreibt die gültigen Nachrichten und fängt einen Ausfall der Datenbank ab.
     *
     * Ein Ausfall (Datenbank steht, Verbindung tot, Zeitgrenze) liegt nicht an
     * den Nachrichten. Sie sind noch nicht bestätigt und liegen sicher in der
     * Queue. Wir warten kurz und werfen den Fehler WEITER: der Container legt
     * dann den ganzen Stapel zurück, und der Broker liefert ihn erneut. Das
     * wiederholt sich, bis die Datenbank zurück ist. Ohne die Pause wären es
     * hunderte Versuche pro Sekunde (Spezifikation 3.4).
     *
     * Lehnt die Datenbank dagegen eine Nachricht wegen ihres Inhalts ab, ist
     * das kein Ausfall. Darum kümmert sich writeBatchOrOneByOne, hier kommt
     * dieser Fehler nicht an.
     */
    private int writeToDatabase(List<ValidMessage> validMessages, List<RejectedMessage> refusedMessages) {
        try {
            return writeBatchOrOneByOne(validMessages, refusedMessages);
        } catch (RuntimeException exception) {
            log.error("Database write failed, batch of {} goes back to the queue in {} ms: {}",
                    validMessages.size(), retryDelayMillis, exception.getMessage());
            waitBeforeRetry();
            throw exception;
        }
    }

    /**
     * Versucht zuerst den ganzen Stapel in einer Transaktion: der Normalfall
     * und der schnelle Weg.
     *
     * Lehnt die Datenbank den Stapel wegen des INHALTS ab (zu langer Text,
     * verletzte Regel der Tabelle), meldet Spring das als
     * DataIntegrityViolationException. Dann ist mindestens eine Nachricht eine
     * Giftnachricht: legten wir den Stapel zurück, käme sie bei jeder
     * Wiederholung wieder, und die Queue stünde für immer still. Darum
     * versuchen wir die Nachrichten dann einzeln und finden so die schuldige.
     *
     * Jeder andere Fehler ist ein Ausfall und geht weiter an writeToDatabase.
     */
    private int writeBatchOrOneByOne(List<ValidMessage> validMessages, List<RejectedMessage> refusedMessages) {
        List<ChatMessage> chatMessages = toChatMessages(validMessages);
        try {
            return messageRepository.saveAll(chatMessages);
        } catch (DataIntegrityViolationException exception) {
            String sqlState = sqlStateOf(exception);
            log.warn("Database refused the batch of {} (SQL state {}), writing the messages one by one",
                    validMessages.size(), sqlState);
            return writeOneByOne(validMessages, refusedMessages);
        }
    }

    /**
     * Schreibt jede Nachricht in einer eigenen Transaktion. Lehnt die
     * Datenbank eine ab, kommt sie samt Grund in refusedMessages, die übrigen
     * werden gespeichert.
     *
     * Das kostet eine Transaktion pro Nachricht statt einer pro Stapel. Es
     * trifft aber nur den seltenen Stapel, in dem eine Giftnachricht steckt.
     *
     * Fällt die Datenbank mitten in der Schleife aus, geht der Fehler weiter
     * an writeToDatabase, und der ganze Stapel kommt zurück. Was bis dahin
     * gespeichert war, ist beim nächsten Versuch ein harmloses Duplikat.
     */
    private int writeOneByOne(List<ValidMessage> validMessages, List<RejectedMessage> refusedMessages) {
        int inserted = 0;
        for (ValidMessage valid : validMessages) {
            ChatMessage chatMessage = valid.chatMessage();
            List<ChatMessage> singleMessage = List.of(chatMessage);
            try {
                int insertedNow = messageRepository.saveAll(singleMessage);
                inserted = inserted + insertedNow;
            } catch (DataIntegrityViolationException exception) {
                String sqlState = sqlStateOf(exception);
                String reason = "refused by database (SQL state " + sqlState + ")";
                Message original = valid.message();
                RejectedMessage refused = new RejectedMessage(original, reason);
                refusedMessages.add(refused);
            }
        }
        return inserted;
    }

    /**
     * Nimmt aus jeder gültigen Nachricht die gelesene Fassung. Die Datenbank
     * braucht das Original aus der Queue nicht.
     */
    private List<ChatMessage> toChatMessages(List<ValidMessage> validMessages) {
        List<ChatMessage> chatMessages = new ArrayList<>();
        for (ValidMessage valid : validMessages) {
            ChatMessage chatMessage = valid.chatMessage();
            chatMessages.add(chatMessage);
        }
        return chatMessages;
    }

    /**
     * Holt den SQL-Zustand aus dem Fehler: einen fünfstelligen Code, der die Art
     * des Problems nennt, etwa 22001 für "Text zu lang für die Spalte".
     *
     * Die Fehlermeldung selbst geben wir absichtlich NICHT weiter. Der Treiber
     * schreibt die ganze Zeile hinein, also auch den Nachrichtentext (geprüft
     * am 30.10.2026), und der gehört nicht ins Log.
     */
    private String sqlStateOf(DataIntegrityViolationException exception) {
        Throwable cause = exception.getMostSpecificCause();
        if (cause instanceof SQLException sqlException) {
            return sqlException.getSQLState();
        }
        return "unknown";
    }

    /**
     * Legt den Verbraucher für die Dauer der Pause schlafen. Wird er dabei
     * unterbrochen (der Dienst wird gerade beendet), merkt er sich das und
     * macht sofort weiter, statt das Beenden aufzuhalten.
     */
    private void waitBeforeRetry() {
        try {
            Thread.sleep(retryDelayMillis);
        } catch (InterruptedException interrupted) {
            Thread currentThread = Thread.currentThread();
            currentThread.interrupt();
        }
    }

    /**
     * Legt abgelehnte Nachrichten in chat.dlq. Aufgerufen wird sie zweimal pro
     * Stapel, für zwei Arten von Ablehnung:
     *
     * VOR dem Schreiben für die ungültigen, die der Parser ablehnt. Eine
     * ungültige Nachricht ist ungültig, egal was die Datenbank gerade tut. So
     * wartet sie nicht auf einen Stapel, der vielleicht nie durchkommt. Der
     * Preis: scheitert danach die Datenbank, kommt der ganze Stapel zurück, und
     * die ungültige Nachricht landet bei jeder Wiederholung noch einmal in
     * chat.dlq. Doppelt weggelegt ist besser als verloren (Spezifikation 3.2).
     *
     * NACH dem Schreiben für die, die erst die Datenbank abgelehnt hat. Welche
     * das sind, weiss man erst nach dem Schreibversuch.
     */
    private void moveRejectedToDeadLetterQueue(List<RejectedMessage> rejectedMessages) {
        for (RejectedMessage rejected : rejectedMessages) {
            deadLetterPublisher.publish(rejected.message(), rejected.reason());
        }
    }

    /**
     * Schreibt eine Zeile pro Stapel ins Log: wie viele kamen, wie viele neu
     * in die Tabelle, wie viele waren Duplikate, wie viele ungültig, wie viele
     * hat die Datenbank abgelehnt, und wie lange es dauerte.
     */
    private void logBatch(int received, int inserted, int valid, int rejected, int refused, long startNanos) {
        long durationNanos = System.nanoTime() - startNanos;
        long durationMillis = durationNanos / 1_000_000;
        int duplicates = valid - inserted - refused;

        log.info("Batch done: {} received, {} inserted, {} duplicates, {} invalid, {} refused by database, {} ms",
                received, inserted, duplicates, rejected, refused, durationMillis);
    }
}
