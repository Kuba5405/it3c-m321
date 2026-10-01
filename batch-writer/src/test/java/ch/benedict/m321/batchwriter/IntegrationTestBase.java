package ch.benedict.m321.batchwriter;

import ch.benedict.m321.batchwriter.dto.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.MountableFile;

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
     * Beginnt jeden Test mit leerer Tabelle. Sonst sähe ein Test die Zeilen
     * des vorherigen und schlüge scheinbar grundlos fehl.
     */
    @BeforeEach
    void emptyMessageTable() {
        jdbcTemplate.execute("TRUNCATE TABLE message");
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
}
