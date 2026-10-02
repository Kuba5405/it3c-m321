# batch-writer — Umsetzungsplan

**Ziel:** Der `batch-writer` holt Nachrichten aus `chat.persist` und legt sie in Stapeln dauerhaft
in PostgreSQL ab. Er übersteht doppelte Nachrichten, einen Datenbankausfall und mehrere Instanzen.

**Spezifikation:** [`spec-batch-writer.md`](spec-batch-writer.md). Sie ist **vor** diesem Plan
entstanden und steht im Git-Log vor ihm. Dieser Plan sagt nur noch, in welcher Reihenfolge gebaut wird.

**Vorbild:** [`plan-chat-service.md`](plan-chat-service.md). Kleine Schritte, jeder mit Test, jeder ein Commit.

## Globale Vorgaben

Diese Punkte gelten für **jede** Aufgabe:

- **Java 21**, Spring Boot 3.5.16, Spring AMQP, `JdbcTemplate` (kein JPA), PostgreSQL 16, RabbitMQ 3.13.
- **Code auf Englisch**, alles andere (Kommentare, Javadoc, Commit-Messages, Doku) **auf Deutsch**.
- **Keine verschachtelten Aufrufe.** Ein Ergebnis pro Zeile, in eine benannte Variable. Gilt auch in Tests.
- **Keine Streams und keine Lambdas** im `batch-writer/` (CLAUDE.md, Szenario S8). Eine `for`-Schleife
  kann man laut vorlesen.
- **Über jeder Klasse und jeder Methode steht ein Kommentar**, der erklärt, *warum* es sie gibt.
  Auch über Testmethoden und Konstruktoren.
- **Lombok** für `@Slf4j` und `@RequiredArgsConstructor`, Java-`record` für Datenklassen.
- **Kein `ports:`-Eintrag** in `docker-compose.yml`. **Keine Geheimnisse im Repository**: Werte nur in `.env`,
  im Repository nur `.env.example`.
- **Test vor Code.** Jede Aufgabe beginnt mit dem Test, der fehlschlägt, und endet mit demselben Test grün.
- **Ein Thema pro Commit.** Jeder Commit endet mit dieser Zeile:
  ```
  Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
  ```
  Ab dem Commit `refactor: Aufbau des Test-Containers an einer Stelle bündeln` steht dort
  `Claude Opus 5.5`, weil das Werkzeug das Modell gewechselt hat.
- **Voraussetzung:** Docker läuft (Testcontainers startet echte Container).

## Reihenfolge im Überblick

| # | Aufgabe | Commit-Message | Szenario |
|---|---|---|---|
| 1 | Lombok auf jedem JDK | `fix: Lombok auch mit JDK 23 und neuer ausführen` | S1 |
| 2 | Tabelle und Modul-Gerüst | `feat: Tabelle message mit Primärschlüssel und Index anlegen` | S2 |
| 3 | Nachricht lesen und prüfen | `feat: Nachrichten aus JSON lesen und prüfen` | S5 |
| 4 | Stapel in die Datenbank schreiben | `feat: Stapel in einer Transaktion in die Datenbank schreiben` | S3, S4, S5 |
| 5 | Queues und Stapel-Verbraucher einrichten | `feat: Queues deklarieren und Stapel-Verbraucher konfigurieren` | S3, S6 |
| 6 | Ungültige Nachrichten weglegen | `feat: Ungültige Nachrichten in chat.dlq veröffentlichen` | – |
| 7 | Alles zusammenstecken | `feat: Nachrichten aus chat.persist stapelweise speichern` | S3, S4, S5 |
| 8 | Datenbankausfall überstehen | `feat: Bei Datenbankausfall warten und Stapel zurückgeben` | S7 |
| 9 | Dockerfile und docker-compose | `chore: batch-writer und postgres in docker-compose abbilden` | S2, S6 |
| 10 | Abnahmeskripte | `test: Abnahmeskripte für die Szenarien S1 bis S8` | S1 bis S8 |
| 11 | README nachführen | `docs: README um batch-writer und postgres ergänzen` | – |

---

## Aufgabe 1: Lombok auf jedem JDK

**Warum zuerst:** Ohne diesen Fix baut nichts. Maven läuft auf dem Rechner, auf dem der Plan entstand,
mit JDK 26. Ab JDK 23 führt `javac` Annotation-Prozessoren nicht mehr von selbst aus, Lombok bleibt
stumm, und schon der bestehende `chat-service` bricht mit `cannot find symbol: log` ab. Das Szenario
S1 (`mvn clean test`) wäre damit rot, bevor eine Zeile des `batch-writer` existiert.

**Dateien:** `pom.xml` (Eltern-POM)

**Test:** `mvn -q clean test` im Wurzelverzeichnis. Vorher: `BUILD FAILURE` (nachgewiesen am
01.10.2026, Protokoll im Chat), nachher: grün.

**Was gebaut wird:** `annotationProcessorPaths` für Lombok im `maven-compiler-plugin`.

**Schnittstellen:**
- Verbraucht: nichts
- Stellt bereit: Ein Build, in dem Lombok auf jedem JDK läuft (Grundlage für S1)

**Ablauf:**
- [x] Test schreiben und Fehlschlag sehen: `mvn -q clean test` → `BUILD FAILURE`, `cannot find symbol: log` im bestehenden `chat-service`
- [x] Code schreiben, Test grün: Ganzer Lauf grün
- [x] Committen:

```bash
git add pom.xml
git commit -m "fix: Lombok auch mit JDK 23 und neuer ausführen" \
  -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

## Aufgabe 2: Tabelle und Modul-Gerüst

**Warum jetzt:** Die Tabelle ist der Boden, auf dem alles steht. Jeder spätere Test schreibt hinein,
also muss sie als Erstes stimmen. Das Modul-Gerüst gehört dazu, weil es für den ersten Test gebraucht wird.

**Dateien:**
- `postgres/init.sql` — Tabelle und Index aus Spezifikation 4.1
- `batch-writer/pom.xml` — Modul mit Test-Abhängigkeiten (JUnit, Testcontainers, PostgreSQL-Treiber)
- `pom.xml` — Modul eintragen
- `batch-writer/src/test/java/ch/benedict/m321/batchwriter/SchemaIntegrationTest.java`

**Test:** Startet einen echten PostgreSQL-Container mit **derselben** `init.sql`, die später im Stack
läuft (Testcontainers kopiert sie nach `/docker-entrypoint-initdb.d/`), und liest per
`information_schema` nach: alle sechs Spalten mit richtigem Typ, `NOT NULL`, `id` ist
Primärschlüssel, Index `(room_id, sent_at DESC)` existiert, keine Tabelle `room`.

**Schnittstelle für später:** Tabelle `message`.

**Schnittstellen:**
- Verbraucht: Aufgabe 1
- Stellt bereit: Tabelle `message` (Spalten, Primärschlüssel `id`, Index), Modul `ch.benedict.m321:batch-writer`

**Ablauf:**
- [x] Test schreiben und Fehlschlag sehen: `mvn -q -pl batch-writer test` ohne `init.sql` → 3 von 4 Tests rot (keine Spalten, kein Schlüssel, kein Index)
- [x] Code schreiben, Test grün: 4 Tests grün
- [x] Committen:

```bash
git add pom.xml postgres batch-writer
git commit -m "feat: Tabelle message mit Primärschlüssel und Index anlegen" \
  -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

## Aufgabe 3: Nachricht lesen und prüfen

**Warum jetzt:** Reine Logik ohne Broker und Datenbank, daher die schnellsten Tests. Und die Frage
«Was ist eine gültige Nachricht?» (Spezifikation 3.2) muss beantwortet sein, bevor wir schreiben.

**Dateien:**
- `batch-writer/.../dto/ChatMessage.java` — Record mit den sechs Feldern
- `batch-writer/.../service/MessageParser.java` — Bytes zu `ChatMessage`, oder `InvalidMessageException`
- `batch-writer/.../service/InvalidMessageException.java`
- `batch-writer/.../service/MessageParserTest.java`

**Test (Einheitstest, ohne Container):**
- der **echte** Körper, den der `chat-service` am 01.10.2026 geliefert hat (9 Nachkommastellen), wird gelesen
- kein JSON, leerer Körper, fehlende Pflichtfelder, leerer `content`, unlesbare UUID, `U+0000` im Text: jeweils `InvalidMessageException` mit Begründung
- ein unbekanntes Zusatzfeld stört nicht

**Entscheidung dahinter:** Spezifikation E4, wir lesen die Bytes selbst.

**Schnittstellen:**
- Verbraucht: nichts aus früheren Aufgaben
- Stellt bereit: `ChatMessage(UUID id, UUID roomId, String senderId, String senderName, String content, Instant sentAt)`, `MessageParser.parse(byte[])` → `ChatMessage` oder `InvalidMessageException`

**Ablauf:**
- [x] Test schreiben und Fehlschlag sehen: `-Dtest=MessageParserTest` → Übersetzungsfehler, `ChatMessage` und `MessageParser` gibt es noch nicht
- [x] Code schreiben, Test grün: 11 Tests grün
- [x] Committen:

```bash
git add batch-writer
git commit -m "feat: Nachrichten aus JSON lesen und prüfen" \
  -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

## Aufgabe 4: Stapel in die Datenbank schreiben

**Warum jetzt:** Der Kern des Dienstes, und er hängt nur an der Datenbank. Wer ihn **ohne** Queue
testet, sieht ein Datenbankproblem nicht erst im Zusammenspiel mit RabbitMQ.

**Dateien:**
- `batch-writer/.../BatchWriterApplication.java`
- `batch-writer/src/main/resources/application.yml` — Datenquelle, Zeitgrenzen
- `batch-writer/.../service/MessageRepository.java` — `saveAll(List<ChatMessage>)`, `@Transactional`, `batchUpdate`, `ON CONFLICT (id) DO NOTHING`
- `batch-writer/src/test/.../IntegrationTestBase.java` — startet die Datenbank einmal für alle Tests
- `batch-writer/.../service/MessageRepositoryIntegrationTest.java`
- `batch-writer/pom.xml` — `spring-boot-starter-jdbc`, Lombok

**Test (echte Datenbank):**
- 3 Nachrichten rein, 3 Zeilen da, Spalten stimmen (auch `sent_at`)
- **dieselbe Nachricht zweimal** in zwei Aufrufen: eine Zeile (S5)
- **dieselbe Nachricht zweimal im selben Stapel**: eine Zeile
- Rückgabewert zählt nur neu eingefügte Zeilen
- **1000 Nachrichten verbrauchen genau eine Schreib-Transaktion** (S4). Gemessen mit
  `txid_current()`: nur Schreib-Transaktionen bekommen eine Nummer, der Unterschied vorher/nachher
  ist ihre Zahl. Das ist sofort und genau, im Gegensatz zu `pg_stat_database`, das verzögert nachzieht.
- schlägt eine Zeile fehl, ist **nichts** vom Stapel in der Tabelle (alles oder nichts)

**Schnittstellen:**
- Verbraucht: Tabelle `message` (Aufgabe 2), `ChatMessage` (Aufgabe 3)
- Stellt bereit: `MessageRepository.saveAll(List<ChatMessage>)` → Zahl der neu eingefügten Zeilen, eine Transaktion pro Aufruf; `IntegrationTestBase`

**Ablauf:**
- [x] Test schreiben und Fehlschlag sehen: `-Dtest=MessageRepositoryIntegrationTest` → Übersetzungsfehler, `MessageRepository` fehlt. Nach dem Grünwerden Gegenprobe: ohne `@Transactional` waren es 4 statt 1 Schreib-Transaktion, der Test schlug an
- [x] Code schreiben, Test grün: 8 Tests grün
- [x] Committen:

```bash
git add batch-writer
git commit -m "feat: Stapel in einer Transaktion in die Datenbank schreiben" \
  -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

## Aufgabe 5: Queues und Stapel-Verbraucher einrichten

**Warum jetzt:** Datenbankseite steht. Jetzt die Queue-Seite. Die Deklaration kommt vor dem Verbraucher,
weil ein Verbraucher an eine nicht vorhandene Queue gar nicht erst hängen kann (Spezifikation 2.4, Punkt 1).

**Dateien:**
- `batch-writer/.../config/QueueNames.java`
- `batch-writer/.../config/RabbitConfig.java` — `persistQueue` (mit Dead-Letter-Argumenten), `deadLetterQueue`, `batchContainerFactory` (`batchSize`, **`batchReceiveTimeout`**, `receiveTimeout`, `prefetch`, ein Verbraucher)
- `batch-writer/src/main/resources/application.yml` — RabbitMQ-Zugang, `BATCH_SIZE`, `BATCH_TIMEOUT_MS`
- `IntegrationTestBase.java` — dazu der RabbitMQ-Container
- `batch-writer/.../config/RabbitConfigIntegrationTest.java`
- `batch-writer/pom.xml` — `spring-boot-starter-amqp`, Testcontainers-RabbitMQ

**Test (echter RabbitMQ):**
- `chat.persist` und `chat.dlq` existieren, nachdem der Kontext gestartet ist
- `chat.persist` trägt `x-dead-letter-exchange` und `x-dead-letter-routing-key = chat.dlq` (gelesen mit `rabbitmqctl list_queues name arguments`, also so, wie es auch ein Mensch nachprüfen würde)

**Schnittstellen:**
- Verbraucht: nichts aus früheren Aufgaben ausser `IntegrationTestBase`
- Stellt bereit: `QueueNames.PERSIST_QUEUE`, `QueueNames.DEAD_LETTER_QUEUE`, Beans `persistQueue`, `deadLetterQueue`, `batchContainerFactory`

**Ablauf:**
- [x] Test schreiben und Fehlschlag sehen: `-Dtest=RabbitConfigIntegrationTest` → Übersetzungsfehler, `QueueNames` fehlt
- [x] Code schreiben, Test grün: 3 Tests grün, Modul 26
- [x] Committen:

```bash
git add batch-writer
git commit -m "feat: Queues deklarieren und Stapel-Verbraucher konfigurieren" \
  -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

## Aufgabe 6: Ungültige Nachrichten weglegen

**Warum jetzt:** Klein und unabhängig, aber der Verbraucher in Aufgabe 7 braucht ihn. Vorher bauen heisst: Aufgabe 7 bleibt kurz.

**Dateien:**
- `batch-writer/.../service/DeadLetterPublisher.java` — veröffentlicht eine Nachricht unverändert in `chat.dlq`, ergänzt `x-error-reason`
- `batch-writer/.../service/DeadLetterPublisherIntegrationTest.java`

**Test (echter RabbitMQ):** Körper und Header kommen unverändert an, `x-error-reason` ist gesetzt,
die ursprünglichen Eigenschaften (`content_type`) bleiben erhalten.

**Schnittstellen:**
- Verbraucht: `QueueNames` (Aufgabe 5)
- Stellt bereit: `DeadLetterPublisher.publish(Message, String reason)`

**Ablauf:**
- [x] Test schreiben und Fehlschlag sehen: `-Dtest=DeadLetterPublisherIntegrationTest` → Übersetzungsfehler, `DeadLetterPublisher` fehlt
- [x] Code schreiben, Test grün: 2 Tests grün, Modul 28
- [x] Committen:

```bash
git add batch-writer
git commit -m "feat: Ungültige Nachrichten in chat.dlq veröffentlichen" \
  -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

## Aufgabe 7: Alles zusammenstecken

**Warum jetzt:** Alle Teile sind einzeln getestet. Der Verbraucher selbst ist nur noch Ablauf:
lesen, schreiben, weglegen, bestätigen (Spezifikation 3.2). Hier zeigt sich, ob das Zusammenspiel
stimmt, und hier stehen die Tests zu S3, S4 und S5.

**Dateien:**
- `batch-writer/.../consumer/PersistQueueListener.java` — `@RabbitListener` auf `chat.persist`, nimmt `List<Message>`
- `batch-writer/.../consumer/PersistQueueListenerIntegrationTest.java`

**Tests (echter RabbitMQ **und** echte Datenbank):**
- **S3:** eine Nachricht im Format des `chat-service` landet in der Tabelle, die Queue ist leer
- **S5:** dieselbe Nachricht **zweimal direkt** in `chat.persist`, mit **nur** `content_type: application/json`: genau eine Zeile, `chat.dlq` leer
- **ungültige Nachricht neben einer gültigen:** die gültige steht in der Tabelle, die ungültige in `chat.dlq` mit Begründung
- **S4:** Verbraucher anhalten, 1000 Nachrichten in die Queue legen, Verbraucher starten: alle 1000 in der Tabelle, und es waren höchstens 10 Schreib-Transaktionen (Spezifikation: erwartet 2)
- ein Stapel wird nach `BATCH_TIMEOUT_MS` auch dann geschrieben, wenn er nicht voll ist (eine einzelne Nachricht)

**Schnittstellen:**
- Verbraucht: `MessageParser` (3), `MessageRepository` (4), `batchContainerFactory` (5), `DeadLetterPublisher` (6)
- Stellt bereit: `PersistQueueListener.onBatch(List<Message>)` an `chat.persist`

**Ablauf:**
- [x] Test schreiben und Fehlschlag sehen: 5 von 5 Tests rot: `expected: <1> but was: <0>`, nichts wird gespeichert, weil noch niemand die Queue liest
- [x] Code schreiben, Test grün: 5 Tests grün, Modul 33. Log zeigt 2 Stapel zu 500 für den Rückstand von 1000
- [x] Committen:

```bash
git add batch-writer
git commit -m "feat: Nachrichten aus chat.persist stapelweise speichern" \
  -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

## Aufgabe 8: Datenbankausfall überstehen

**Warum jetzt:** Der Dienst funktioniert im Normalfall. Jetzt kommt das, wofür die Queue da ist.
Es ist ein eigener Commit, weil es ein eigenes Verhalten mit einem eigenen Risiko ist: ein Fehler
hier wirft Nachrichten weg oder dreht eine Endlosschleife.

**Dateien:**
- `PersistQueueListener.java` — bei Fehler im Schreiben: `RETRY_DELAY_MS` warten, Fehler weiterwerfen
- `batch-writer/src/main/resources/application.yml` — `RETRY_DELAY_MS`
- `batch-writer/.../consumer/DatabaseOutageIntegrationTest.java`

**Test (echter RabbitMQ und echte Datenbank):** Die Datenbank wird **angehalten** (`docker pause` per
Testcontainers-Docker-Client), 5 Nachrichten gehen in die Queue. Geprüft wird:
- während des Ausfalls: **keine** Zeile in der Tabelle, aber die Nachrichten sind **nicht verloren** (in der Queue, unbestätigt oder zurückgelegt)
- nach dem Fortsetzen: alle 5 in der Tabelle, ohne dass der Verbraucher neu gestartet wurde
- `chat.dlq` bleibt leer (Ausfall ist kein Fehler der Nachricht)

**Schnittstellen:**
- Verbraucht: `PersistQueueListener` (Aufgabe 7)
- Stellt bereit: Verhalten bei Datenbankfehler: `RETRY_DELAY_MS` warten, Stapel zurückgeben

**Ablauf:**
- [x] Test schreiben und Fehlschlag sehen: Die Sicherheitstests waren schon grün (die Queue hält die Nachrichten auch ohne eigenen Code). Rot war der Test auf die Pause: 0 gemeldete Fehlversuche. Gegenprobe nach dem Fix: mit `RETRY_DELAY_MS=0` 23 Versuche in 5 s, der Test schlug an
- [x] Code schreiben, Test grün: 3 Tests grün, Modul 36
- [x] Committen:

```bash
git add batch-writer
git commit -m "feat: Bei Datenbankausfall warten und Stapel zurückgeben" \
  -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

## Aufgabe 9: Dockerfile und docker-compose

**Warum jetzt:** Bisher ist der Dienst nur im Test gelaufen. Erst jetzt kann sich zeigen, ob er im Stack
läuft: Netzwerk, Anmeldedaten, Startreihenfolge. Davor wäre es raten.

**Dateien:**
- `batch-writer/Dockerfile` — wie beim `chat-service`: bauen, dann laufen. **Kein** `EXPOSE`, der Dienst hat keinen Port.
- `docker-compose.yml` — Dienste `postgres` und `batch-writer`, Datenträger `postgres-data`, `healthcheck`, `depends_on` mit `service_healthy`, kein `ports:`, kein `container_name`
- `.env.example` — `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_DB`, `BATCH_SIZE`, `BATCH_TIMEOUT_MS`, `RETRY_DELAY_MS`

**Test:** `docker compose up -d --build`, dann: alle vier Dienste `running`, `grep -n "ports:" docker-compose.yml`
ohne Treffer, `\d message` in `psql` zeigt die Tabelle, 5 Nachrichten über `POST /messages` stehen nach
wenigen Sekunden in der Tabelle. Der Dienst bleibt ohne Webserver am Leben.

**Schnittstellen:**
- Verbraucht: das lauffähige Modul aus Aufgabe 1 bis 8
- Stellt bereit: Compose-Dienste `postgres` und `batch-writer` im Netz `chat-net`, ohne Port

**Ablauf:**
- [x] Test vorbereiten: einen roten Test vorab gab es hier nicht, denn der Stack-Test kann erst laufen, wenn es den Dienst im Stack gibt. Beim Lesen erkannt (nicht beobachtet): das `chat-service`-Dockerfile hätte das neue Modul-POM vermisst
- [x] Code schreiben, Test grün: Stack läuft, 5 Nachrichten über `POST /messages` stehen in der Tabelle
- [x] Committen:

```bash
git add batch-writer chat-service docker-compose.yml .env.example
git commit -m "chore: batch-writer und postgres in docker-compose abbilden" \
  -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

## Aufgabe 10: Abnahmeskripte

**Warum jetzt:** Die Szenarien S1 bis S8 sollen wiederholbar sein, nicht einmal von Hand gelaufen.
Skripte kommen zuletzt, weil sie das fertige System prüfen und selbst keine Logik enthalten.

**Dateien:**
- `scripts/verify-batch-writer.sh` — S2 bis S7 in dieser Reihenfolge auf demselben Stack, jeweils mit der Messung aus Spezifikation 6
- `scripts/check-code-rules.sh` — S8: keine Streams und Lambdas, Kommentar über jeder Klasse und Methode, keine `.env` im Repository

**Test:** Beide Skripte laufen gegen den fertigen Stand und melden für jedes Szenario `PASS` oder `FAIL`.
Zusätzlich wird `check-code-rules.sh` an einer absichtlich verletzten Kopie geprüft: es muss `FAIL` melden,
sonst misst es nichts.

**Schnittstellen:**
- Verbraucht: der Stack aus Aufgabe 9
- Stellt bereit: `scripts/verify-batch-writer.sh` (S2 bis S7), `scripts/check-code-rules.sh` (S8)

**Ablauf:**
- [x] Test schreiben und Fehlschlag sehen: `check-code-rules.sh` gegen eine absichtlich verletzte Kopie → `FAIL` für Stream-Wort, Lambda, Methodenreferenz, fehlende Kommentare, `.env`
- [x] Code schreiben, Test grün: Gegen den echten Stand: S1 bis S8 `PASS`
- [x] Committen:

```bash
git add scripts
git commit -m "test: Abnahmeskripte für die Szenarien S1 bis S8" \
  -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

## Aufgabe 11: README nachführen

**Warum zuletzt:** Das README beschreibt, was **ist**. Erst wenn alles läuft, stimmt die Tabelle «Stand».

**Dateien:** `README.md` — Tabelle «Stand» (`batch-writer`, `postgres` auf «vorhanden»), Abschnitt «Bauen,
testen, starten» (PostgreSQL per Testcontainers, `scripts/`), Verweise auf Spezifikation und Plan.

**Test:** Die im README genannten Befehle werden einmal von oben nach unten ausgeführt.

**Schnittstellen:**
- Verbraucht: alles
- Stellt bereit: README mit Stand, Befehlen und Verweisen

**Ablauf:**
- [x] Test vorbereiten: kein automatischer Test; geprüft wird, ob die Befehle im README stimmen. Vorher nannte das README weder `postgres` noch `batch-writer`
- [x] Code schreiben, Test grün: Alle README-Befehle wortgleich ausgeführt, die Nachricht «Hallo README» stand in der Tabelle
- [x] Committen:

```bash
git add README.md
git commit -m "docs: README um batch-writer und postgres ergänzen" \
  -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

## Nachtrag: Randfall-Versuche (nicht im ursprünglichen Plan)

**Warum:** Bis Aufgabe 10 waren die Szenarien und der Normalfall geprüft, nicht aber, was passiert, wenn
etwas schiefgeht, woran niemand gedacht hat. Auf Wunsch wurde der fertige Dienst gezielt auf Fehler
angegriffen, am laufenden Stack (Spezifikation, Abschnitt 8).

**Gefunden und behoben**, je mit einem Test, der vorher rot war:

| Fund | Wirkung | Commit |
|---|---|---|
| `sentAt` weit in der Zukunft | Ein einziges Stück Nachricht blockiert die Queue für immer | `fix: Zeitpunkte ausserhalb von Jahr 1 bis 9999 in die Dead-Letter-Queue legen` |
| `sentAt` weit in der Vergangenheit | Die Datenbank speichert stillschweigend `-infinity` | derselbe Commit |
| `BATCH_TIMEOUT_MS=0` | 100 % CPU im Leerlauf | `fix: Unsinnige Stapel-Einstellungen beim Start ablehnen` |

**Gefunden, nicht behoben, in der Spezifikation benannt** (Abschnitt 7, Punkte 6 bis 8): der Zombie bei
falschen Queue-Argumenten, eine unvalidierte negative `RETRY_DELAY_MS`, das stille `?` bei einzelnen
Surrogat-Zeichen.

**Korrigiert wurde ausserdem ein falscher Satz der Spezifikation** (3.1: «der Dienst startet nicht»).

**Weitere Abweichungen vom ursprünglichen Plan**, ehrlich nachgetragen:

| Abweichung | Warum |
|---|---|
| Aufgabe 9 änderte auch `chat-service/Dockerfile` | Das Eltern-POM listet jetzt `batch-writer`. Maven liest dessen POM auch beim Bau nur des `chat-service`, das Dockerfile kopierte es nicht mit, der Image-Build wäre gebrochen |
| Zusätzliche Testklasse `RabbitConfigTest` (Aufgabe 5, nachgeliefert im Commit zu den Einstellungen) | `BATCH_TIMEOUT_MS=0` liess den Dienst mit 100 % CPU laufen |
| Commit `refactor: Verschachtelte Aufrufe im batch-writer auflösen` | Die Prüfung des fertigen Codes gegen `CLAUDE.md` fand im Testcode Aufrufe im Aufruf (`assertEquals(1, countRows())`) und Ketten. Verhalten unverändert, alle Tests wie vorher grün |
| Plan-Tabelle, Aufgabe 3: Commit-Message hatte «pruefen» statt «prüfen» | Beim Ersetzen der Umlaute übersehen. Der Abgleich Plan gegen `git log` hat es gefunden |

### Alle Commits nach Aufgabe 11, in dieser Reihenfolge

Damit Plan und `git log` lückenlos übereinstimmen:

| # | Commit-Message | Thema |
|---|---|---|
| 12 | `fix: Zeitpunkte ausserhalb von Jahr 1 bis 9999 in die Dead-Letter-Queue legen` | Randfall C |
| 13 | `fix: Unsinnige Stapel-Einstellungen beim Start ablehnen` | Randfall J |
| 14 | `docs: Spezifikation und Plan nach den Randfall-Versuchen nachführen` | Spezifikation §7, §8, dieser Nachtrag |
| 15 | `refactor: Verschachtelte Aufrufe im batch-writer auflösen` | CLAUDE.md, ein Ergebnis pro Zeile |
| 16 | `docs: Plan an den Git-Log angleichen und Abweichungen nachtragen` | «pruefen» → «prüfen» |
| 17 | `refactor: Aufbau des Test-Containers an einer Stelle bündeln` | doppelten Container-Aufbau entfernt (`PostgresTestContainer`) |
| 18 | `docs: Kommentare im batch-writer korrigieren` | Tippfehler, ein falscher Satz über den Startabbruch |
| 19 | `docs: Durchsatz des batch-writer gemessen und Abnahme S6 präzisiert` | Spezifikation §1.1 und §8 Block M |
| 20 | `docs: Umsetzungsplan im Aufbau des Vorbilds ergänzen` | Schnittstellen, Ablauf und Commit-Befehl je Aufgabe |
| 21 | `docs: Abschluss-Prüfung im Plan abgehakt` | erst nach bestandener Abnahme im frischen Klon |

---

## Abschluss-Prüfung

- [ ] `mvn clean test` im Wurzelverzeichnis: alles grün, in einem Lauf (S1)
- [ ] `scripts/verify-batch-writer.sh`: S2 bis S7 `PASS`
- [ ] `scripts/check-code-rules.sh`: S8 `PASS`
- [ ] `git status --short` sauber, `git ls-files | grep -x .env` ohne Treffer
- [ ] `git log --oneline` liest sich wie die Tabelle oben
- [ ] Lief ein Schritt anders als geplant, steht die Abweichung **hier im Plan nachgetragen**,
      mit eigenem Commit, nicht verschwiegen
