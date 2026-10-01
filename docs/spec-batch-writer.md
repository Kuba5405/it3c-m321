# Spezifikation: batch-writer

**Stand:** 01.10.2026 · **Bezug:** [`PLANUNG.md`](../PLANUNG.md) Abschnitte 3.5, 3.6, 3.7 und 4 ·
**Gegenstück:** [`plan-batch-writer.md`](plan-batch-writer.md) (Umsetzungsplan)

Diese Spezifikation reicht, um den Dienst ohne Rückfrage zu bauen. Jede Aussage über das
Verhalten des `chat-service` ist **gemessen**, nicht angenommen. Wie, steht in Abschnitt 2.4.

---

## 1. Zweck und Abgrenzung

### 1.1 Was der Dienst tut

Der `batch-writer` holt Nachrichten aus der Queue `chat.persist` und legt sie dauerhaft in
der PostgreSQL-Tabelle `message` ab. Er ist der **einzige Schreiber** in diese Tabelle.

Der Grund für seine Existenz ist die Last. PLANUNG.md rechnet mit 100'000 Nachrichten pro
Minute, das sind 1'667 pro Sekunde. 1'667 einzelne Schreibvorgänge pro Sekunde verträgt
keine Datenbank auf Dauer. Der `batch-writer` fasst die Nachrichten zu Stapeln zusammen
und schreibt jeden Stapel in **einer** Transaktion. Bei einem Stapel von 500 sind das
rund 3 Transaktionen pro Sekunde statt 1'667.

### 1.2 Was der Dienst bewusst nicht tut

| Nicht Teil des Dienstes | Warum nicht |
|---|---|
| Chat-Historie lesen | Lesen ist Sache des `chat-service` (PLANUNG.md 3.1). Ein Schreiber, der auch liest, hätte zwei Gründe, sich zu ändern |
| Räume und Mitgliedschaften | Die Aufgabe schliesst sie aus. Darum gibt es keine Tabelle `room` und keinen Fremdschlüssel (siehe 5, E9) |
| Login, Token-Prüfung | Der Dienst ist von aussen nicht erreichbar. Das Gateway ist der einzige Wachposten |
| Eine eigene REST-Schnittstelle | Er hat keinen Webserver und keinen Port. Seine einzige Schnittstelle ist die Queue |
| Nachrichten ausliefern | Das ist der Zustellweg (`chat.delivery`), nicht der Schreibweg |
| Exactly-once behaupten | Das kann ein verteiltes System nicht garantieren. Wir garantieren etwas Bescheideneres, siehe 3.3 |

---

## 2. Vertrag

### 2.1 Eingang: was auf der Queue ankommt

| Eigenschaft | Wert |
|---|---|
| Queue | `chat.persist`, durable |
| Queue-Argumente | `x-dead-letter-exchange` = `""` (leer), `x-dead-letter-routing-key` = `chat.dlq` |
| Header `content_type` | `application/json` |
| Header `content_encoding` | `UTF-8` (kann fehlen, der Dienst darf sich nicht darauf verlassen) |
| Header `delivery_mode` | `2` (persistent) |
| Header `__TypeId__` | `ch.benedict.m321.chatservice.dto.ChatMessage`. **Der Dienst ignoriert ihn**, siehe 5, E4 |
| Körper | ein JSON-Objekt mit genau diesen sechs Feldern |

Beispiel eines echten Körpers, vom laufenden `chat-service` gelesen:

```json
{"id":"46998f49-b5aa-457a-a489-cf729adb142a",
 "roomId":"3f2b1c4e-0000-0000-0000-000000000001",
 "senderId":"anna",
 "senderName":"Anna Muster",
 "content":"Hallo",
 "sentAt":"2026-10-01T07:38:47.713518425Z"}
```

| JSON-Feld | Typ | Pflicht | Bedeutung |
|---|---|---|---|
| `id` | UUID als Text | ja | vom `chat-service` vergeben, identifiziert die Nachricht im ganzen System |
| `roomId` | UUID als Text | ja | der Raum |
| `senderId` | Text | ja, nicht leer | `sub` aus Keycloak |
| `senderName` | Text | ja, nicht leer | Anzeigename, bewusst denormalisiert |
| `content` | Text | ja, nicht leer | der Nachrichtentext |
| `sentAt` | ISO-8601 mit Zone, bis 9 Nachkommastellen | ja | vom Server gesetzter Zeitpunkt |

Unbekannte zusätzliche Felder werden ignoriert. Ein neuer Sender darf ein Feld ergänzen,
ohne den Schreibweg zu brechen.

**Der Szenario-Fall S5** legt dieselbe Nachricht mit **nur** dem Header
`content_type: application/json` auf die Queue, also ohne `__TypeId__`, ohne
`content_encoding`, ohne `delivery_mode`. Der Dienst muss damit genauso arbeiten wie mit
einer Nachricht des `chat-service`.

### 2.2 Ausgang: die Tabelle

Eine Zeile pro Nachricht in `message`, Aufbau in Abschnitt 4.1. Die Felder werden 1:1
übernommen. Die einzige Veränderung: `sentAt` hat im JSON bis zu 9 Nachkommastellen,
PostgreSQL speichert `timestamptz` mit 6. Die letzten drei werden gerundet.

### 2.3 Ausgang: die Dead-Letter-Queue

Nachrichten, die der Dienst **nicht lesen kann**, landen unverändert in `chat.dlq`,
ergänzt um den Header `x-error-reason` (Text, kurze Begründung auf Englisch). Was als
«nicht lesbar» gilt, steht in 3.2.

### 2.4 Woher wir das wissen

Am 01.10.2026 wurde der bestehende Stack gestartet (`docker compose up -d --build`), eine
Nachricht per `POST /messages` gesendet und aus der Queue gelesen, ohne sie zu
verbrauchen:

```bash
docker compose exec rabbitmq rabbitmqctl list_queues name messages consumers arguments
docker run --rm --network chat-net curlimages/curl -s -u "$RABBITMQ_USER:$RABBITMQ_PASSWORD" \
  -X POST http://rabbitmq:15672/api/queues/%2F/chat.persist/get \
  -H 'content-type: application/json' \
  -d '{"count":1,"ackmode":"ack_requeue_true","encoding":"auto"}'
```

Dabei sind drei Dinge aufgefallen, die in keinem Dokument standen und die den Entwurf
bestimmen:

1. **Die Queues existieren vor der ersten Nachricht nicht.** Der `chat-service` deklariert sie
   erst beim ersten Senden. Der `batch-writer` kann also nicht darauf warten, dass es sie
   gibt: er deklariert sie selbst, mit denselben Argumenten (siehe 3.1).
2. **`__TypeId__` zeigt auf eine Klasse des `chat-service`.** Im `batch-writer` gibt es sie
   nicht (siehe 5, E4).
3. **`sentAt` hat 9 Nachkommastellen**, die Datenbank nur 6 (siehe 2.2).

---

## 3. Verhalten

### 3.1 Start

1. Der Dienst **deklariert** `chat.persist` und `chat.dlq`, beide durable, `chat.persist`
   mit den Argumenten aus 2.1. Deklarieren ist wiederholbar: gibt es die Queue schon mit
   denselben Argumenten, passiert nichts. Stimmen die Argumente nicht, lehnt der Broker ab
   und der Dienst startet nicht. Das ist gewollt: ein stiller Unterschied wäre schlimmer.
2. Er hängt **einen** Verbraucher an `chat.persist`. Skaliert wird über Instanzen, nicht
   über Threads (siehe 5, E10).
3. Im Compose-Stack startet er erst, wenn RabbitMQ und PostgreSQL `healthy` melden.

### 3.2 Normalfall: ein Stapel

```
Nachricht aus chat.persist ──► Stapel sammeln ──► lesen und prüfen ──► eine Transaktion ──► ACK
```

1. **Sammeln.** Der Container sammelt Nachrichten, bis `BATCH_SIZE` erreicht sind oder
   `BATCH_TIMEOUT_MS` seit Beginn des Sammelns vergangen sind. Was zuerst eintritt, beendet
   den Stapel. Ein Stapel von einer einzigen Nachricht ist erlaubt.
2. **Lesen und prüfen.** Jeder Körper wird als JSON in eine `ChatMessage` gelesen. Eine
   Nachricht ist **ungültig**, wenn
   - der Körper kein gültiges JSON-Objekt ist, oder
   - `id`, `roomId` oder `sentAt` fehlen oder nicht lesbar sind, oder
   - `senderId`, `senderName` oder `content` fehlen oder nur aus Leerzeichen bestehen, oder
   - ein Textfeld das Zeichen `U+0000` enthält. PostgreSQL kann es in `text` nicht speichern
     und würde den ganzen Stapel ablehnen.
3. **Schreiben.** Alle gültigen Nachrichten des Stapels gehen in **einer Transaktion** in
   die Tabelle, mit `INSERT ... ON CONFLICT (id) DO NOTHING`. Ist die `id` schon da, wird
   die Zeile übersprungen. Es gewinnt die erste.
4. **Ungültige weglegen.** Jede ungültige Nachricht wird in `chat.dlq` veröffentlicht, mit
   `x-error-reason`.
5. **Bestätigen.** Erst wenn 3 **und** 4 durch sind, endet die Verarbeitung normal und der
   Container bestätigt (ACK) den ganzen Stapel.
6. **Protokollieren.** Eine Zeile pro Stapel: wie viele empfangen, eingefügt, als Duplikat
   übersprungen, in die Dead-Letter-Queue gelegt, und die Dauer.

**Warum erst die Datenbank, dann die Dead-Letter-Queue?** Schlägt die Datenbank fehl, wird der
Stapel wiederholt (3.4). Hätten wir die ungültigen Nachrichten vorher weggelegt, stünden sie
bei jeder Wiederholung erneut in `chat.dlq`.

**Warum ACK erst nach dem COMMIT?** Weil eine bestätigte Nachricht aus der Queue verschwindet.
Würden wir vorher bestätigen und danach abstürzen, wäre die Nachricht weg und nirgends
gespeichert.

### 3.3 Garantie

| Zusage | Bedeutung |
|---|---|
| **At-least-once** von der Queue bis zur Tabelle | Eine Nachricht wird nie bestätigt, bevor sie in der Tabelle steht. Sie kann aber mehr als einmal ankommen |
| **Keine doppelte Zeile** | Primärschlüssel `id` plus `ON CONFLICT DO NOTHING`. Ein Duplikat ist harmlos, nicht verhindert |
| **Keine Reihenfolge** | Stapel, Wiederholungen und mehrere Instanzen mischen die Reihenfolge. Wer liest, sortiert nach `sent_at` (PLANUNG.md, offener Punkt 3) |

### 3.4 Fehlerfälle

| Fall | Szenario | Was passiert | Warum |
|---|---|---|---|
| **Dieselbe Nachricht zweimal** | S5 | Beide kommen an. Die Zeile entsteht einmal, `chat.dlq` bleibt leer | Ein Duplikat ist kein Fehler, sondern der erwartete Preis von At-least-once. `ON CONFLICT DO NOTHING` macht es harmlos. Es in die Dead-Letter-Queue zu legen, würde den Normalfall als Störung melden |
| **Duplikat im selben Stapel** | S5 | Wie oben: die erste Zeile gewinnt, die zweite wird übersprungen | Beide Nachrichten können im selben Stapel liegen, wenn sie kurz nacheinander ankommen. Der Primärschlüssel entscheidet, nicht die Reihenfolge der Ankunft |
| **Datenbank nicht erreichbar** | S7 | Der Stapel wird nicht bestätigt. Der Dienst wartet `RETRY_DELAY_MS`, wirft dann den Fehler weiter, der Container legt den **ganzen Stapel zurück** in die Queue, der Broker liefert ihn erneut. Das wiederholt sich, bis die Datenbank wieder da ist. Der Dienst läuft die ganze Zeit weiter | Die Nachrichten liegen sicher in der Queue, solange niemand bestätigt. Warten ohne Pause würde hunderte Versuche pro Sekunde auslösen. Endlos wiederholen, weil der Ausfall nicht an der Nachricht liegt: nach drei Versuchen in die Dead-Letter-Queue zu legen, wie PLANUNG.md 3.5 es für **kaputte** Nachrichten vorsieht, würde bei 15 Sekunden Ausfall gesunde Nachrichten wegwerfen |
| **Datenbank kommt zurück** | S7 | Die Verbindungen aus dem Pool sind tot. Der Pool prüft sie beim Ausleihen, ersetzt sie, der nächste Versuch gelingt. Spätestens `RETRY_DELAY_MS` plus Verbindungsaufbau nach dem Neustart steht alles in der Tabelle | Kein Neustart von Hand nötig |
| **Datenbank antwortet nicht (hängt)** | S7 | Zeitgrenzen beenden den Versuch: 5 s für den Verbindungsaufbau, 30 s für eine Antwort, 5 s Wartezeit auf eine Verbindung aus dem Pool | Ohne Zeitgrenzen würde ein eingefrorener Server den Verbraucher ohne Fehlermeldung ewig festhalten |
| **Nachricht nicht lesbar** | – | Sie geht mit `x-error-reason` in `chat.dlq`, der Stapel läuft weiter | Eine kaputte Nachricht darf die gesunden nicht aufhalten. Würde sie den Stapel scheitern lassen, käme sie bei jeder Wiederholung wieder und die Queue stünde still |
| **Schreiber war weg, Queue füllt sich** | S4 | Beim Start liegen N Nachrichten in der Queue. Der Dienst holt sie in Stapeln zu `BATCH_SIZE`: für 1000 Nachrichten 2 Stapel, also 2 Transaktionen. Nichts geht verloren | Die Queue ist der Puffer. Dafür ist sie da, und genau das zeigt S4: Transaktionen steigen mit der Zahl der **Stapel**, nicht der Nachrichten |
| **Absturz mitten im Stapel** | – | Es wurde noch nicht bestätigt, also liefert der Broker den Stapel erneut | At-least-once |
| **Absturz nach COMMIT, vor ACK** | – | Der Stapel kommt erneut. Alle Zeilen existieren schon, alle werden übersprungen | Genau dafür gibt es den Primärschlüssel |
| **Zwei Instanzen** | S6 | Beide hängen an derselben Queue. Der Broker gibt jede Nachricht an genau eine. Jede Instanz hat ihre eigenen Stapel und Transaktionen | Competing Consumers. Es gibt keinen gemeinsamen Zustand zwischen den Instanzen, also nichts, worin sie sich stören könnten |
| **Zwei Instanzen starten gleichzeitig** | S6 | Keine stört die andere beim Start | Der Dienst legt **kein Schema an**. Zwei gleichzeitige `CREATE TABLE` können sich gegenseitig abbrechen (siehe 5, E8) |
| **RabbitMQ nicht erreichbar** | – | Der Container versucht die Verbindung alle 5 Sekunden neu aufzubauen. Der Dienst läuft weiter | Das ist das Standardverhalten von Spring AMQP, wir ändern nichts |

---

## 4. Datenmodell und Konfiguration

### 4.1 Tabelle

```sql
CREATE TABLE message (
    id          uuid        PRIMARY KEY,
    room_id     uuid        NOT NULL,
    sender_id   varchar     NOT NULL,
    sender_name varchar     NOT NULL,
    content     text        NOT NULL,
    sent_at     timestamptz NOT NULL
);

CREATE INDEX message_room_id_sent_at_idx ON message (room_id, sent_at DESC);
```

| Spalte | Begründung |
|---|---|
| `id` Primärschlüssel | Der Schlüssel macht Duplikate harmlos (3.4). Er stammt vom `chat-service`, nicht von der Datenbank: nur so ist dieselbe Nachricht bei einer Wiederholung **dieselbe** |
| `varchar` ohne Längengrenze | Eine Grenze, die der `chat-service` nicht kennt, würde gültige Nachrichten erst in der Datenbank ablehnen und den ganzen Stapel scheitern lassen |
| `NOT NULL` überall | Die Prüfung in 3.2 stellt es sicher. Die Datenbank ist die zweite Verteidigungslinie |
| `timestamptz` | Zeitpunkt mit Zone. `timestamp` ohne Zone wäre je nach Servereinstellung eine andere Uhrzeit |
| Index `(room_id, sent_at DESC)` | Die einzige Abfrage des Lesepfads: «die letzten 50 Nachrichten eines Raums» (PLANUNG.md 3.7). Er kostet beim Schreiben etwas. Wir legen ihn trotzdem jetzt an, weil ein Index auf eine grosse Tabelle nachträglich lange dauert |
| **Kein** Fremdschlüssel auf `room` | Es gibt keine Tabelle `room` (1.2). Mit Fremdschlüssel würde jede Nachricht an einen unbekannten Raum den Stapel scheitern lassen |

**Wo das Schema entsteht:** in `postgres/init.sql`, die in den Postgres-Container unter
`/docker-entrypoint-initdb.d/` eingehängt wird. Das Image führt sie **einmal** aus, beim
ersten Start mit leerem Datenverzeichnis. Folge: ein geänderter Aufbau verlangt
`docker compose down -v`. Das ist der Preis, siehe 5, E8.

### 4.2 Umgebungsvariablen

| Variable | Wo gesetzt | Vorgabe | Bedeutung |
|---|---|---|---|
| `RABBITMQ_HOST` | Compose | `localhost` | Host des Brokers. Im Stack `rabbitmq` |
| `RABBITMQ_USER` | `.env` | `guest` | Benutzer des Brokers. Besteht schon |
| `RABBITMQ_PASSWORD` | `.env` | `guest` | Passwort des Brokers. Besteht schon |
| `POSTGRES_HOST` | Compose | `localhost` | Host der Datenbank. Im Stack `postgres` |
| `POSTGRES_PORT` | – | `5432` | Port der Datenbank. Nur für Läufe ausserhalb von Docker |
| `POSTGRES_USER` | `.env` | – | Benutzer. Legt der Postgres-Container beim ersten Start an |
| `POSTGRES_PASSWORD` | `.env` | – | Passwort dazu. Nur Beispielwert im Repository |
| `POSTGRES_DB` | `.env` | – | Name der Datenbank |
| `BATCH_SIZE` | `.env` | `500` | Grösse eines Stapels. Zugleich die Vorabholmenge (prefetch): der Broker darf höchstens so viele unbestätigte Nachrichten pro Verbraucher ausliefern |
| `BATCH_TIMEOUT_MS` | `.env` | `200` | Höchstdauer des Sammelns eines Stapels in Millisekunden |
| `RETRY_DELAY_MS` | `.env` | `2000` | Pause nach einem Datenbankfehler, bevor der Stapel zurückgelegt wird |

`POSTGRES_USER`, `POSTGRES_PASSWORD` und `POSTGRES_DB` haben **keine** Vorgabe im Dienst: ohne
sie gibt es keine Datenbank, und ein heimlicher Ersatzwert wäre ein Passwort im Quelltext.

**Wie lange wartet eine Nachricht im Dienst höchstens?** Der Container wartet pro einzelne
Nachricht ebenfalls höchstens `BATCH_TIMEOUT_MS`. Im ungünstigsten Fall ist das knapp
`2 × BATCH_TIMEOUT_MS`, also etwa 400 ms plus die Schreibzeit.

### 4.3 Compose-Dienste

| Dienst | Image / Build | Netz | Port nach aussen | Besonderheit |
|---|---|---|---|---|
| `postgres` | `postgres:16-alpine` | `chat-net` | **keiner** | Benannter Datenträger `postgres-data`, damit die Nachrichten einen Neustart überleben. `healthcheck` mit `pg_isready` |
| `batch-writer` | Build aus `batch-writer/Dockerfile` | `chat-net` | **keiner** | Wartet auf `rabbitmq` und `postgres` (`service_healthy`). **Kein** `container_name`, sonst lässt er sich nicht mit `--scale` vervielfachen |

---

## 5. Entscheidungen und verworfene Alternativen

| # | Entscheidung | Verworfen | Begründung |
|---|---|---|---|
| E1 | **Stapel zu 500 oder 200 ms**, danach ein INSERT-Stapel in einer Transaktion | Einzel-INSERTs; ein fester Takt ohne Obergrenze | 500 und 200 ms stammen aus PLANUNG.md 4.1. Einzel-INSERTs scheitern an der Last, ein Takt ohne Obergrenze liesse einen Stapel unbegrenzt wachsen |
| E2 | Zeitlimit über **`batchReceiveTimeout`** | `receiveTimeout` allein | `receiveTimeout` gilt **pro einzelne Nachricht**. Kommt alle 150 ms eine Nachricht, wird die Wartezeit nie überschritten und der Stapel füllt sich bis 500: bei 7 Nachrichten pro Sekunde wären das über eine Minute Verzögerung. Im Quelltext von Spring AMQP 3.2.12 nachgelesen (`SimpleMessageListenerContainer.doReceiveAndExecute`) |
| E3 | **ACK erst nach COMMIT**, bei Fehler den ganzen Stapel zurücklegen | Vorher bestätigen; einzeln bestätigen | Siehe 3.2. Einzeln bestätigen wären 500 Netzwerkaufrufe statt einem |
| E4 | **Körper selbst als JSON lesen**, `__TypeId__` ignorieren | `Jackson2JsonMessageConverter` des Brokers | Der Konverter sucht die im Header genannte Klasse und fände sie im `batch-writer` nicht. S5 schickt den Header gar nicht mit. Wer die Bytes selbst liest, hängt nur vom JSON ab, nicht vom Klassennamen eines anderen Dienstes (so schon in `ChatMessage` des `chat-service` begründet) |
| E5 | **`ON CONFLICT (id) DO NOTHING`** | Vorher mit `SELECT` prüfen; `DO UPDATE`; Duplikat als Fehler | `SELECT` und `INSERT` sind zwei Schritte, zwischen denen eine zweite Instanz dieselbe Zeile anlegen kann. `DO UPDATE` würde eine erste Nachricht durch eine spätere überschreiben. Ein Duplikat als Fehler zu behandeln würde S5 verletzen |
| E6 | **`JdbcTemplate` mit `batchUpdate`**, Transaktion per `@Transactional` | JPA; ein handgebautes `INSERT` mit tausend Platzhaltern | PLANUNG.md 2.1: kein JPA im Schreiber. `batchUpdate` ist genau das Muster, das wir zeigen wollen. `@Transactional` heisst: Spring beginnt die Transaktion vor der Methode und macht COMMIT, wenn sie normal endet, sonst ROLLBACK |
| E7 | **Endlos wiederholen** bei Datenbankfehlern, mit Pause | Nach drei Versuchen in die Dead-Letter-Queue | Siehe 3.4: der Fehler liegt nicht an der Nachricht |
| E8 | **Schema in `postgres/init.sql`**, vom Postgres-Image beim ersten Start ausgeführt | Der Dienst legt es selbst an (`CREATE TABLE IF NOT EXISTS`); Flyway | `CREATE TABLE IF NOT EXISTS` ist bei zwei gleichzeitigen Instanzen nicht sicher (S6). Flyway löst das mit einer Sperre, kostet aber bei **jedem** Start einige Datenbank-Transaktionen: S4 zählt diese mit, und das Werkzeug wäre eines mehr zum Erklären. Die Init-Datei kostet zur Laufzeit nichts. Nachteil: sie läuft nur einmal, spätere Änderungen verlangen `down -v` |
| E9 | **Keine Tabelle `room`, kein Fremdschlüssel** | `room` anlegen | Ausserhalb der Aufgabe. Der `chat-service` prüft nicht, ob ein Raum existiert, der Schreibweg darf es daher auch nicht |
| E10 | **Ein Verbraucher pro Instanz** | Mehrere Threads pro Instanz | `rabbitmqctl list_queues ... consumers` zeigt dann die Zahl der Instanzen, und S6 («beide Instanzen hängen an der Queue») ist ablesbar. Mehrere Threads wären ein zweiter Weg zur Skalierung neben `--scale` |
| E11 | **Queues im Dienst selbst deklarieren** | Auf den `chat-service` warten | Gemessen (2.4): der `chat-service` deklariert erst beim ersten Senden |
| E12 | **Ungültige Nachrichten selbst in `chat.dlq` veröffentlichen** | Per `basicReject` ablehnen und den Broker weiterleiten lassen | Einzelnes Ablehnen verlangt, dass wir selbst bestätigen und dafür Lieferkennungen verwalten. Der automatische Modus mit «alles oder nichts pro Stapel» ist einfacher zu erklären. Preis: das Veröffentlichen ist nicht bestätigt (siehe 7) |

---

## 6. Abnahmekriterien

Jedes Kriterium nennt den Befehl, der es misst. Die Befehle S2 bis S7 laufen in dieser
Reihenfolge auf **demselben** Stack, ohne Aufräumen dazwischen. `scripts/verify-batch-writer.sh`
führt sie aus. Wer die Befehle einzeln tippen will, findet sie hier.

Vorbereitung: `cp .env.example .env`. In den Befehlen steht `set -a; . ./.env; set +a`, damit die
Variablen aus `.env` gesetzt sind.

| Nr | Kriterium | Befehl, der es misst | Bestanden, wenn |
|---|---|---|---|
| **S1** | Alle Tests grün, in einem Lauf | `mvn clean test` im Wurzelverzeichnis | `BUILD SUCCESS`, Exit-Code 0, Tests laufen mit echter Queue und echter Datenbank |
| **S2** | Alle Dienste laufen, kein Port veröffentlicht | `docker compose up -d --build`, dann `docker compose ps --format '{{.Name}} {{.State}} {{.Ports}}'` und `grep -n "ports:" docker-compose.yml` | `postgres`, `rabbitmq`, `chat-service`, `batch-writer` alle `running`. Keine Zeile mit `0.0.0.0` oder `->`. `grep` ohne Treffer |
| **S3** | 1000 Nachrichten über `POST /messages` | 1000 `POST /messages` aus dem Netz `chat-net` (Skript: Schleife mit `curl` in einem Container). Dann alle paar Sekunden `docker compose exec postgres psql -U $POSTGRES_USER -d $POSTGRES_DB -tAc "SELECT count(*) FROM message"` und `docker compose exec rabbitmq rabbitmqctl list_queues name messages` | Nach höchstens 60 s nach dem letzten Senden ist `count` = 1000 und `chat.persist` hat `0` Nachrichten |
| **S4** | Schreiber weg, 1000 senden, Schreiber zurück | `docker compose stop batch-writer`, 1000 Nachrichten senden, `chat.persist` zeigt 1000. Vorher `SELECT xact_commit FROM pg_stat_database WHERE datname = current_database()` merken, `docker compose start batch-writer`, warten, wieder lesen | `count` steigt um 1000, nichts verloren. Der Zuwachs von `xact_commit` ist höchstens 100. Erwartet sind wenige Transaktionen, nämlich 2 Stapel plus die Messabfragen selbst |
| **S5** | Dieselbe Nachricht zweimal, direkt in die Queue | Zwei Veröffentlichungen mit nur `content_type: application/json` an `chat.persist` (Skript: Management-Schnittstelle, `POST /api/exchanges/%2F/amq.default/publish`, gleiche `id`). Dann `SELECT count(*) FROM message WHERE id = '<id>'` und `rabbitmqctl list_queues name messages` | `count` = 1, `chat.dlq` hat `0` Nachrichten |
| **S6** | Zwei Instanzen | `docker compose up -d --scale batch-writer=2`, `rabbitmqctl list_queues name consumers`, dann 1000 Nachrichten, `SELECT count(*), count(DISTINCT id) FROM message` | `consumers` = 2 für `chat.persist`. Alle Nachrichten da, `count` = `count(DISTINCT id)`, keine doppelten Zeilen |
| **S7** | Postgres steht still | `docker compose stop postgres`, 300 Nachrichten senden, 15 s warten, `docker compose start postgres`. Dann wie bei S3 zählen. Dazu `docker compose ps batch-writer` | Nach höchstens 90 s sind alle 300 in der Tabelle. Der `batch-writer` läuft mit unveränderter Startzeit (`docker inspect -f '{{.State.StartedAt}}'`), also kein Neustart von Hand |
| **S8** | Quelltext von `batch-writer/` hält die Regeln aus `CLAUDE.md` | `scripts/check-code-rules.sh` | Keine Streams (aus Vorsicht auch keine Lambdas) in `batch-writer/`. Über jeder Klasse und jeder Methode steht ein Kommentar. `git ls-files` listet keine `.env` |

Dazu ein Test pro Eigenschaft, die der Abschnitt 3 behauptet (Plan: Aufgaben 4 bis 8):
Duplikat (S5), Datenbankausfall (S7), Stapel mit wenigen Transaktionen (S4), ungültige
Nachricht, korrektes Lesen des echten `chat-service`-Formats.

---

## 7. Offene Punkte und bekannte Grenzen

Ehrlich benannt, nicht weggeschwiegen:

| # | Punkt | Folge | Möglicher Weg |
|---|---|---|---|
| 1 | **Kaputte Datenzeile, die die Datenbank ablehnt** (nach der Prüfung in 3.2 unwahrscheinlich, aber denkbar) | Der Dienst würde den Stapel endlos wiederholen und die Queue stünde still | Beim Wiederholen die Nachrichten einzeln versuchen und die abgelehnten in die Dead-Letter-Queue legen. Nicht gebaut, weil ohne bekannten Auslöser |
| 2 | **Veröffentlichen in `chat.dlq` ohne Bestätigung** (kein Publisher Confirm) | Stürzt der Broker in genau diesem Moment ab, geht eine ungültige Nachricht verloren. Gültige nie | Publisher Confirms einschalten (PLANUNG.md: erst messen, dann härten) |
| 3 | **Die Datenbank wächst** um etwa 1,2 GB pro Stunde bei Dauerlast | Platz | Siehe PLANUNG.md, offener Punkt 2. Nicht Teil dieser Aufgabe |
| 4 | **Schema ändert sich nur mit `down -v`** | Kein Weg für spätere Änderungen an einer laufenden Datenbank | Ein Migrationswerkzeug, wenn es die erste echte Änderung gibt |
| 5 | **Ein eingeschleuster Fehler im Dienst** (zum Beispiel falsches SQL) wird wie ein Datenbankausfall endlos wiederholt | Die Nachrichten bleiben sicher in der Queue, aber nichts wird geschrieben. Die Fehlermeldung im Log ist das einzige Signal | Überwachung der Queue-Tiefe (PLANUNG.md, Schritt 5) |
