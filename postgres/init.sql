-- Schema des Chat-Verlaufs.
--
-- Diese Datei führt das Postgres-Image EINMAL aus: beim ersten Start mit
-- leerem Datenverzeichnis (docker-compose hängt sie unter
-- /docker-entrypoint-initdb.d/ ein). Der batch-writer legt selbst nichts an,
-- denn zwei Instanzen, die gleichzeitig CREATE TABLE ausführen, können sich
-- gegenseitig abbrechen. Siehe docs/spec-batch-writer.md, Abschnitt 4.1 und E8.
--
-- Eine geänderte Datei wirkt erst nach "docker compose down -v".

-- Eine Zeile pro Nachricht.
CREATE TABLE message (
    -- Vom chat-service vergeben, nicht von der Datenbank. Nur so ist eine
    -- erneut gelieferte Nachricht dieselbe Nachricht, und ON CONFLICT
    -- kann das Duplikat erkennen.
    id          uuid        PRIMARY KEY,
    room_id     uuid        NOT NULL,
    -- sub aus Keycloak
    sender_id   varchar     NOT NULL,
    -- Anzeigename, bewusst denormalisiert, damit die Historie auch nach dem
    -- Löschen eines Kontos lesbar bleibt
    sender_name varchar     NOT NULL,
    content     text        NOT NULL,
    -- Mit Zeitzone, sonst hängt die Uhrzeit von der Servereinstellung ab
    sent_at     timestamptz NOT NULL
);

-- Für die einzige Abfrage des Lesepfads: die letzten Nachrichten eines
-- Raums, neueste zuerst.
CREATE INDEX message_room_id_sent_at_idx ON message (room_id, sent_at DESC);
