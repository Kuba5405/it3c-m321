package ch.benedict.m321.batchwriter;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prüft die Tabelle gegen eine ECHTE Datenbank, aufgebaut aus derselben
 * Datei postgres/init.sql, die später im docker-compose-Stack läuft.
 *
 * Warum so? Ein Test, der ein eigenes, nachgebautes Schema benutzt, würde
 * grün bleiben, auch wenn die Datei im Stack falsch ist.
 */
@Testcontainers
class SchemaIntegrationTest {

    /**
     * Die Datenbank kommt aus PostgresTestContainer und hat dieselbe init.sql wie der Stack.
     * Testcontainers startet sie vor dem ersten Test und beendet sie nach dem letzten.
     */
    @Container
    static PostgreSQLContainer<?> postgres = PostgresTestContainer.create();

    /**
     * Alle sechs Spalten aus PLANUNG.md 3.7 sind da, in dieser Reihenfolge,
     * mit dem richtigen Typ, und keine darf leer sein.
     */
    @Test
    void messageTableHasTheSixColumns() throws SQLException {
        String sql = "SELECT column_name || ':' || data_type || ':' || is_nullable "
                + "FROM information_schema.columns "
                + "WHERE table_name = 'message' ORDER BY ordinal_position";

        List<String> columns = readFirstColumn(sql);

        List<String> expected = List.of(
                "id:uuid:NO",
                "room_id:uuid:NO",
                "sender_id:character varying:NO",
                "sender_name:character varying:NO",
                "content:text:NO",
                "sent_at:timestamp with time zone:NO");
        assertEquals(expected, columns);
    }

    /**
     * Die id ist der Primärschlüssel. Ohne ihn gäbe es keinen Schutz vor
     * doppelten Nachrichten, und ON CONFLICT hätte nichts, worauf es greift.
     */
    @Test
    void idIsThePrimaryKey() throws SQLException {
        String sql = "SELECT kcu.column_name "
                + "FROM information_schema.table_constraints tc "
                + "JOIN information_schema.key_column_usage kcu "
                + "ON tc.constraint_name = kcu.constraint_name "
                + "WHERE tc.table_name = 'message' AND tc.constraint_type = 'PRIMARY KEY'";

        List<String> primaryKeyColumns = readFirstColumn(sql);

        List<String> expectedColumns = List.of("id");
        assertEquals(expectedColumns, primaryKeyColumns);
    }

    /**
     * Der Index für die einzige Abfrage des Lesepfads: die letzten
     * Nachrichten eines Raums, neueste zuerst.
     */
    @Test
    void indexSupportsTheHistoryQuery() throws SQLException {
        String sql = "SELECT indexdef FROM pg_indexes "
                + "WHERE tablename = 'message' AND indexname = 'message_room_id_sent_at_idx'";

        List<String> indexDefinitions = readFirstColumn(sql);

        assertEquals(1, indexDefinitions.size());
        String definition = indexDefinitions.get(0);
        assertTrue(definition.contains("(room_id, sent_at DESC)"), definition);
    }

    /**
     * Räume sind nicht Teil dieser Aufgabe. Eine Tabelle room würde
     * Fremdschlüssel nahelegen, und die würden Nachrichten an unbekannte
     * Räume ablehnen (Spezifikation 5, E9).
     */
    @Test
    void thereIsNoRoomTable() throws SQLException {
        String sql = "SELECT table_name FROM information_schema.tables "
                + "WHERE table_schema = 'public' AND table_name = 'room'";

        List<String> roomTables = readFirstColumn(sql);

        assertTrue(roomTables.isEmpty());
    }

    /**
     * Führt eine Abfrage aus und gibt die erste Spalte aller Zeilen als
     * Liste von Texten zurück. Hält die Testmethoden kurz.
     */
    private List<String> readFirstColumn(String sql) throws SQLException {
        String url = postgres.getJdbcUrl();
        String user = postgres.getUsername();
        String password = postgres.getPassword();
        List<String> values = new ArrayList<>();

        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                String value = resultSet.getString(1);
                values.add(value);
            }
        }
        return values;
    }
}
