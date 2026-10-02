package ch.benedict.m321.batchwriter;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;

/**
 * Baut den Datenbank-Container für die Tests, an genau EINER Stelle.
 *
 * Zwei Testarten brauchen ihn: der Schema-Test (nur die Datenbank) und alle Tests
 * mit dem ganzen Dienst. Beide sollen dieselbe Datenbank bekommen, mit derselben
 * init.sql wie im docker-compose-Stack. Würde jeder sie selbst bauen, könnten sie
 * auseinanderlaufen, und ein Test bliebe grün, obwohl der Stack etwas anderes tut.
 */
public final class PostgresTestContainer {

    /**
     * Diese Klasse ist eine reine Werkzeugsammlung und wird nie erzeugt.
     */
    private PostgresTestContainer() {
    }

    /**
     * Baut den Container Schritt für Schritt auf, startet ihn aber noch nicht.
     *
     * Die init.sql kommt in den Ordner, den das Postgres-Image beim ersten Start
     * ausführt. Genau das macht auch docker-compose. Die Zeitgrenzen sind dieselben
     * wie in application.yml: ein hängender Server darf den Test nicht ewig festhalten.
     */
    public static PostgreSQLContainer<?> create() {
        PostgreSQLContainer<?> container = new PostgreSQLContainer<>("postgres:16-alpine");
        Path initScript = initScriptPath();
        MountableFile initScriptFile = MountableFile.forHostPath(initScript);
        container.withCopyFileToContainer(initScriptFile, "/docker-entrypoint-initdb.d/init.sql");
        container.withUrlParam("connectTimeout", "5");
        container.withUrlParam("socketTimeout", "30");
        return container;
    }

    /**
     * Berechnet den absoluten Pfad der init.sql. Der Test soll nicht davon abhängen,
     * von wo aus Maven oder die Entwicklungsumgebung ihn startet. Der Pfad geht
     * vom Modulordner batch-writer/ eine Ebene nach oben.
     */
    private static Path initScriptPath() {
        Path relativePath = Path.of("..", "postgres", "init.sql");
        Path absolutePath = relativePath.toAbsolutePath();
        return absolutePath.normalize();
    }
}
