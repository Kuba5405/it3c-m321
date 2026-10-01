package ch.benedict.m321.batchwriter;

import ch.benedict.m321.batchwriter.dto.ChatMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import ch.benedict.m321.batchwriter.config.QueueNames;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Gemeinsame Grundlage aller Tests, die den ganzen Dienst brauchen.
 *
 * Datenbank und Broker werden EINMAL gestartet und von allen Testklassen
 * benutzt. Das spart pro Klasse einige Sekunden. Weil alle Klassen dieselbe
 * Konfiguration haben, teilen sie sich auch denselben Spring-Kontext.
 *
 * Der Preis: Datenbank und Queues sind gemeinsamer Zustand. Darum leert jeder
 * Test vorher die Tabelle und die Queues.
 */
@SpringBootTest
@ContextConfiguration(initializers = IntegrationTestBase.ContainerPropertiesInitializer.class)
public abstract class IntegrationTestBase {

    /**
     * Die Datenbank bekommt dieselbe init.sql, die später im Stack läuft.
     * Gestartet wird sie von Hand im static-Block, nicht von JUnit, damit sie
     * für die ganze Testausführung am Leben bleibt.
     */
    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withCopyFileToContainer(
                    MountableFile.forHostPath(initScriptPath()),
                    "/docker-entrypoint-initdb.d/init.sql");

    /**
     * Der Broker, mit Management-Plugin, damit der Test mit rabbitmqctl
     * nachsehen kann, was wirklich in den Queues liegt.
     */
    protected static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management");

    static {
        POSTGRES.start();
        RABBIT.start();
    }

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected RabbitTemplate rabbitTemplate;

    @Autowired
    protected RabbitAdmin rabbitAdmin;

    /** Das Register aller Verbraucher: damit lässt sich der Verbraucher anhalten und starten. */
    @Autowired
    protected RabbitListenerEndpointRegistry listenerRegistry;

    /**
     * Berechnet den absoluten Pfad der init.sql, damit der Test nicht davon
     * abhängt, von wo aus er gestartet wird.
     */
    private static Path initScriptPath() {
        Path relativePath = Path.of("..", "postgres", "init.sql");
        Path absolutePath = relativePath.toAbsolutePath();
        return absolutePath.normalize();
    }

    /**
     * Sagt dem Spring-Kontext, wo die Test-Datenbank gerade lauscht. Der
     * Container bekommt bei jedem Lauf einen anderen Port, darum lässt sich
     * die Adresse nicht in eine Datei schreiben.
     *
     * Absichtlich kein Lambda und kein @DynamicPropertySource: eine kleine
     * Klasse, die man Zeile für Zeile vorlesen kann.
     */
    static class ContainerPropertiesInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        /**
         * Setzt die Verbindungsdaten beider Container, bevor der Kontext
         * gebaut wird. Sie haben Vorrang vor application.yml.
         */
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            TestPropertyValues values = TestPropertyValues.of(
                    "spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                    "spring.datasource.username=" + POSTGRES.getUsername(),
                    "spring.datasource.password=" + POSTGRES.getPassword(),
                    "spring.rabbitmq.host=" + RABBIT.getHost(),
                    "spring.rabbitmq.port=" + RABBIT.getAmqpPort(),
                    "spring.rabbitmq.username=" + RABBIT.getAdminUsername(),
                    "spring.rabbitmq.password=" + RABBIT.getAdminPassword());
            values.applyTo(context);
        }
    }

    /**
     * Beginnt jeden Test mit leerer Tabelle und leeren Queues. Sonst sähe ein
     * Test die Zeilen und Nachrichten des vorherigen und schlüge scheinbar
     * grundlos fehl.
     */
    @BeforeEach
    void emptyTableAndQueues() {
        jdbcTemplate.execute("TRUNCATE TABLE message");
        rabbitAdmin.purgeQueue(QueueNames.PERSIST_QUEUE);
        rabbitAdmin.purgeQueue(QueueNames.DEAD_LETTER_QUEUE);
    }

    /**
     * Baut eine vollständige Nachricht mit frischer id, damit die Tests kurz bleiben.
     * Der Zeitstempel ist auf Mikrosekunden gekürzt, weil die Datenbank
     * nicht genauer speichert: so lässt sich nach dem Lesen exakt vergleichen.
     */
    protected ChatMessage createMessage(String content) {
        UUID messageId = UUID.randomUUID();
        UUID roomId = UUID.randomUUID();
        Instant now = Instant.now();
        Instant sentAt = now.truncatedTo(ChronoUnit.MICROS);
        return new ChatMessage(messageId, roomId, "anna", "Anna Muster", content, sentAt);
    }

    /**
     * Zählt alle Zeilen der Tabelle.
     */
    protected long countRows() {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM message", Long.class);
        return count;
    }

    /**
     * Stellt nach jedem Test sicher, dass der Verbraucher wieder läuft, auch
     * wenn ein Test mittendrin gescheitert ist. Sonst stünde er für alle
     * folgenden Tests still.
     */
    @AfterEach
    void makeSureTheListenerRuns() {
        listenerRegistry.start();
    }

    /**
     * Hält den Verbraucher an. Nachrichten bleiben dann in der Queue liegen,
     * genau wie bei einem gestoppten batch-writer (Szenario S4).
     */
    protected void stopListener() {
        listenerRegistry.stop();
    }

    /**
     * Startet den Verbraucher wieder.
     */
    protected void startListener() {
        listenerRegistry.start();
    }

    /**
     * Baut das JSON genau so, wie der chat-service es schreibt (Spezifikation 2.1),
     * auch mit den neun Nachkommastellen, die Instant.toString() liefert.
     */
    protected String toJson(ChatMessage message) {
        return String.format(
                "{\"id\":\"%s\",\"roomId\":\"%s\",\"senderId\":\"%s\","
                        + "\"senderName\":\"%s\",\"content\":\"%s\",\"sentAt\":\"%s\"}",
                message.id(), message.roomId(), message.senderId(),
                message.senderName(), message.content(), message.sentAt());
    }

    /**
     * Legt eine Nachricht mit allen Eigenschaften in die Queue, die auch der
     * echte chat-service setzt, samt dem Header __TypeId__ (gemessen am 01.10.2026).
     */
    protected void publishLikeChatService(ChatMessage message) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType("application/json");
        properties.setContentEncoding("UTF-8");
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setHeader("__TypeId__", "ch.benedict.m321.chatservice.dto.ChatMessage");
        String json = toJson(message);
        byte[] body = json.getBytes(StandardCharsets.UTF_8);

        rabbitTemplate.send("", QueueNames.PERSIST_QUEUE, new Message(body, properties));
    }

    /**
     * Legt einen Körper mit NUR dem Header content_type in die Queue, ohne
     * __TypeId__. So prüft Szenario S5.
     */
    protected void publishWithOnlyContentType(String json) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType("application/json");
        byte[] body = json.getBytes(StandardCharsets.UTF_8);

        rabbitTemplate.send("", QueueNames.PERSIST_QUEUE, new Message(body, properties));
    }

    /**
     * Wartet höchstens 10 Sekunden, bis die Tabelle so viele Zeilen hat.
     * Gibt danach einfach zurück: ob es gereicht hat, prüft der Test selbst
     * mit einer Zusicherung, die einen verständlichen Fehler zeigt.
     */
    protected void waitForRows(long expectedRows) throws InterruptedException {
        for (int attempt = 0; attempt < 200; attempt++) {
            long rows = countRows();
            if (rows >= expectedRows) {
                return;
            }
            Thread.sleep(50);
        }
    }

    /**
     * Wartet höchstens 10 Sekunden, bis eine Queue genau so viele Nachrichten
     * enthält. Bei Gleichheit oder Ablauf der Zeit geht es weiter.
     */
    protected void waitForQueueCount(String queueName, int expectedCount) throws Exception {
        for (int attempt = 0; attempt < 50; attempt++) {
            int count = queueMessageCount(queueName);
            if (count == expectedCount) {
                return;
            }
            Thread.sleep(200);
        }
    }

    /**
     * Fragt RabbitMQ, wie viele Nachrichten eine Queue enthält, bereite UND
     * noch nicht bestätigte. Gelesen mit rabbitmqctl, so wie es auch ein
     * Mensch nachprüfen würde. Eine Queue, die es nicht gibt, hat 0.
     */
    protected int queueMessageCount(String queueName) throws IOException, InterruptedException {
        ExecResult result = RABBIT.execInContainer("rabbitmqctl", "list_queues", "name", "messages");
        String output = result.getStdout();
        String[] lines = output.split("\n");

        for (int i = 0; i < lines.length; i++) {
            String[] columns = lines[i].trim().split("\\s+");
            if (columns.length == 2 && columns[0].equals(queueName)) {
                return Integer.parseInt(columns[1]);
            }
        }
        return 0;
    }

    /**
     * Gibt die aktuelle Transaktionsnummer der Datenbank zurück. Der Aufruf
     * verbraucht selbst eine. Der Unterschied zweier Aufrufe, minus 1, ist die
     * Zahl der Schreib-Transaktionen dazwischen.
     */
    protected long currentTransactionNumber() {
        Long number = jdbcTemplate.queryForObject("SELECT txid_current()", Long.class);
        return number;
    }
}
