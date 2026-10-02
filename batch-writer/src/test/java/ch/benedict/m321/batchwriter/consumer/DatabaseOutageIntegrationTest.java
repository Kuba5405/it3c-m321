package ch.benedict.m321.batchwriter.consumer;

import ch.benedict.m321.batchwriter.IntegrationTestBase;
import ch.benedict.m321.batchwriter.config.QueueNames;
import ch.benedict.m321.batchwriter.dto.ChatMessage;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.PauseContainerCmd;
import com.github.dockerjava.api.command.UnpauseContainerCmd;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Szenario S7: die Datenbank fällt aus und kommt zurück, und der batch-writer
 * läuft die ganze Zeit weiter, ohne dass jemand ihn neu startet.
 *
 * Die Datenbank wird mit "docker pause" eingefroren: der Container
 * existiert noch, antwortet aber nicht. Das ist der unangenehmere Fall als
 * ein sauber gestoppter Server, denn eine Verbindung scheitert nicht sofort,
 * sondern erst nach einer Zeitgrenze.
 */
class DatabaseOutageIntegrationTest extends IntegrationTestBase {

    private boolean databasePaused = false;

    private ListAppender<ILoggingEvent> listenerLog;

    /**
     * Hängt einen Mitschreiber an das Log des Listeners, um zu zählen, wie
     * oft ein Stapel während des Ausfalls gescheitert ist.
     */
    @BeforeEach
    void startRecordingTheListenerLog() {
        Logger logger = (Logger) LoggerFactory.getLogger(PersistQueueListener.class);
        listenerLog = new ListAppender<>();
        listenerLog.start();
        logger.addAppender(listenerLog);
    }

    /**
     * Räumt nach jedem Test auf, auch wenn er mittendrin scheitert: Datenbank
     * auftauen, Tabellennamen zurücksetzen, Log-Mitschreiber abhängen. Sonst
     * fänden alle folgenden Tests eine eingefrorene Datenbank oder keine Tabelle.
     */
    @AfterEach
    void resumeTheDatabaseAndStopRecording() {
        if (databasePaused) {
            resumeDatabase();
        }
        jdbcTemplate.execute("ALTER TABLE IF EXISTS message_away RENAME TO message");
        Logger logger = (Logger) LoggerFactory.getLogger(PersistQueueListener.class);
        logger.detachAppender(listenerLog);
    }

    /**
     * Der Kern von S7. Während die Datenbank steht, geht keine Nachricht
     * verloren und keine landet fälschlich in der Dead-Letter-Queue. Sobald
     * sie zurück ist, stehen alle in der Tabelle, ohne Neustart des Verbrauchers.
     */
    @Test
    void keepsMessagesSafeWhileTheDatabaseIsDownAndStoresThemAfterwards() throws Exception {
        pauseDatabase();

        for (int i = 0; i < 5; i++) {
            ChatMessage message = createMessage("während des Ausfalls " + i);
            publishLikeChatService(message);
        }
        Thread.sleep(5000);

        int waitingMessages = queueMessageCount(QueueNames.PERSIST_QUEUE);
        int deadLetters = queueMessageCount(QueueNames.DEAD_LETTER_QUEUE);
        assertEquals(5, waitingMessages, "Messages must stay in the queue");
        assertEquals(0, deadLetters, "An outage is not the message's fault");

        resumeDatabase();

        waitForRows(5);
        waitForQueueCount(QueueNames.PERSIST_QUEUE, 0);
        assertRowCount(5);
        assertQueueCount(QueueNames.DEAD_LETTER_QUEUE, 0);
    }

    /**
     * Zwischen zwei Versuchen wird gewartet. Ohne Pause würde der Dienst bei einem
     * SCHNELL scheiternden Fehler hunderte Versuche pro Sekunde machen: Stapel
     * zurück, sofort wieder ausgeliefert, sofort wieder gescheitert.
     *
     * Der schnelle Fehler wird erzeugt, indem die Tabelle kurz einen anderen
     * Namen bekommt: jedes INSERT scheitert sofort mit "relation does not exist".
     * (Bei der eingefrorenen Datenbank oben dauert jeder Versuch schon wegen der
     * Zeitgrenzen Sekunden, dort gäbe es kein Dauerfeuer.)
     *
     * Bei 5 Sekunden und 2 Sekunden Pause sind etwa 3 gescheiterte Versuche zu
     * erwarten. Wir lassen 1 bis 6 zu.
     */
    @Test
    void waitsBetweenRetriesInsteadOfSpinning() throws Exception {
        renameTable("message", "message_away");
        ChatMessage message = createMessage("Pause zwischen den Versuchen");
        publishLikeChatService(message);

        Thread.sleep(5000);

        int failures = countLoggedFailures();
        renameTable("message_away", "message");
        assertTrue(failures >= 1, "Expected at least one logged failure, got " + failures);
        assertTrue(failures <= 6, "Expected few retries, got " + failures);
        waitForRows(1);
        assertRowCount(1);
    }

    /**
     * Ein Neustart der Datenbank macht alle Verbindungen im Pool tot. Wir
     * kappen sie von aussen und schicken sofort Nachrichten: die erste
     * Verbindung, die der Pool herausgibt, ist kaputt, und der Versuch muss
     * später mit einer frischen Verbindung gelingen.
     */
    @Test
    void recoversWhenAllDatabaseConnectionsWereCut() throws Exception {
        jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        jdbcTemplate.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity "
                + "WHERE datname = current_database() AND pid <> pg_backend_pid()");

        for (int i = 0; i < 3; i++) {
            ChatMessage message = createMessage("nach dem Kappen " + i);
            publishLikeChatService(message);
        }

        waitForRows(3);
        waitForQueueCount(QueueNames.PERSIST_QUEUE, 0);
        assertRowCount(3);
        assertQueueCount(QueueNames.DEAD_LETTER_QUEUE, 0);
    }

    /**
     * Gibt der Tabelle einen anderen Namen. Damit scheitert jedes INSERT sofort.
     * Der Test stellt den Namen am Ende selbst wieder her.
     */
    private void renameTable(String from, String to) {
        jdbcTemplate.execute("ALTER TABLE " + from + " RENAME TO " + to);
    }

    /**
     * Friert den Datenbank-Container ein.
     */
    private void pauseDatabase() {
        DockerClient dockerClient = POSTGRES.getDockerClient();
        String containerId = POSTGRES.getContainerId();
        PauseContainerCmd pauseCommand = dockerClient.pauseContainerCmd(containerId);
        pauseCommand.exec();
        databasePaused = true;
    }

    /**
     * Taut den Datenbank-Container wieder auf.
     */
    private void resumeDatabase() {
        DockerClient dockerClient = POSTGRES.getDockerClient();
        String containerId = POSTGRES.getContainerId();
        UnpauseContainerCmd unpauseCommand = dockerClient.unpauseContainerCmd(containerId);
        unpauseCommand.exec();
        databasePaused = false;
    }

    /**
     * Zählt die Fehlermeldungen des Listeners, die vom gescheiterten Schreiben
     * stammen.
     */
    private int countLoggedFailures() {
        int failures = 0;
        for (ILoggingEvent event : listenerLog.list) {
            Level level = event.getLevel();
            String text = event.getFormattedMessage();
            boolean isError = level.equals(Level.ERROR);
            boolean isWriteFailure = text.contains("Database write failed");
            if (isError && isWriteFailure) {
                failures = failures + 1;
            }
        }
        return failures;
    }
}
