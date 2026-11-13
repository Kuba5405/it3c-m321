# Spezifikation: Live-Zustellung

**Stand:** 13.11.2026 · **Bezug:** [`PLANUNG.md`](../PLANUNG.md) Abschnitte 3.4 und 3.5 ·
**Vorbild:** [`spec-batch-writer.md`](spec-batch-writer.md) ·
**Gegenstück:** Umsetzungsplan (noch zu schreiben, entsteht nach dieser Spezifikation)

Diese Spezifikation ist die Grundlage für die Umsetzung. Massgeblich bleiben der
Bewertungsauftrag, `CLAUDE.md`, das bestehende JSON-Format des `chat-service` und die
acht Szenarien S1 bis S8.

**Was gelesen und was gemessen ist.** Aussagen über den bestehenden Code stammen aus dem
Quelltext (Dateien sind genannt). Aussagen über RabbitMQ stammen aus dessen Dokumentation.
**Gemessen am Stack ist nur, was in Abschnitt 5, Spalte «Messwert», steht** (13.11.2026, zwei
vollständige Läufe in je einem frischen Klon, Skripte `scripts/verify-live-delivery.sh` und
`scripts/check-code-rules.sh`). Alles andere ist aus dem Quelltext oder der RabbitMQ-Dokumentation.

---

## 1. Zweck

### 1.1 Was fehlt heute

Nachrichten werden angenommen (`POST /messages`) und sicher gespeichert (`chat.persist` →
`batch-writer` → PostgreSQL). Aber **niemand liest den Zustellweg**. `MessagePublisher`
veröffentlicht jede Nachricht auch am Fanout-Exchange `chat.delivery`. Es ist keine Queue
daran gebunden, und ein Fanout-Exchange ohne gebundene Queue wirft die Nachricht weg. Ein
Client, der gerade im Raum sitzt, erfährt von einer neuen Nachricht nichts.

### 1.2 Was danach möglich ist

Der `chat-service` stellt jede angenommene Nachricht sofort per Server-Sent Events (SSE)
an alle Clients zu, die gerade mit dem Raum der Nachricht verbunden sind, auch wenn der
`batch-writer` und die Datenbank stehen.

### 1.3 Was nicht dazugehört

| Nicht Teil | Warum nicht |
|---|---|
| WebSocket, `web-gateway`, Keycloak, Oberfläche | Bewertet wird genau der feste Endpunkt in 2.2. Das Gateway bleibt in PLANUNG.md, sein Platz ändert sich durch diese Aufgabe nicht |
| Räume und Mitgliedschaften | Wie schon beim `batch-writer`: die Aufgabe schliesst sie aus |
| Dauerhafte Chat-Historie | Lesen aus der Datenbank ist eine andere Aufgabe. Der Replay-Puffer (4.6) ist ein Fenster, keine Historie |
| Eine Dead-Letter-Queue für Live-Nachrichten | Eine Live-Nachricht, die zu spät kommt, ist wertlos. Sie wird verworfen, nicht aufbewahrt (3, Entscheidung E3) |
| Exactly-once über Prozessabstürze | Das kann ein verteiltes System nicht garantieren, siehe 7, Punkt 10 |

---

## 2. Vertrag

### 2.1 Eingang: was am Exchange `chat.delivery` ankommt

- **Typ:** Fanout, durable, nicht auto-delete (`RabbitConfig.deliveryExchange()`). Ein
  Fanout-Exchange ignoriert den Routing-Key und gibt **jeder gebundenen Queue eine eigene Kopie**.
  Darum bekommt jede Instanz jede Nachricht (Begründung in 3).
- **Körper:** ein JSON-Objekt mit genau sechs Feldern. Sie stammen aus dem Record
  `ChatMessage` im `chat-service` und werden von `MessageService.accept` gesetzt:

| JSON-Feld | Typ | Bedeutung |
|---|---|---|
| `id` | UUID als Text | vom Server vergeben, identifiziert die Nachricht im ganzen System |
| `roomId` | UUID als Text | der Raum, an dessen Clients zugestellt wird |
| `senderId` | Text | technische Absenderkennung |
| `senderName` | Text | Anzeigename zum Sendezeitpunkt |
| `content` | Text | der Nachrichtentext |
| `sentAt` | ISO-8601, UTC | vom Server vergebener Zeitpunkt, bis 9 Nachkommastellen |

- **Gleiche Nachricht, zwei Wege.** `MessagePublisher.publish` sendet **dasselbe Objekt**
  zuerst nach `chat.persist`, dann an `chat.delivery`. Der Vertrag ist das JSON und keine
  gemeinsame Klasse (so steht es schon in `ChatMessage.java`).
- **Auch die annehmende Instanz bekommt ihre Kopie vom Broker.** Es gibt keine Abkürzung
  innerhalb der Instanz. Sonst wären «Nachricht von einer anderen Instanz» und «Nachricht von
  mir» zwei Wege mit zwei Fehlerquellen.

**Woher wir das wissen:** aus `ChatMessage.java`, `MessageService.java`, `MessagePublisher.java`
und `RabbitConfig.java`. Ein echter Körper vom laufenden Stack steht in
`spec-batch-writer.md` 2.1 (gemessen am 01.10.2026, 9 Nachkommastellen bei `sentAt`). Am
Zustellweg selbst gelesen und geprüft in `StreamControllerIntegrationTest`: genau sechs Felder, `id:` gleich
`data.id`, `sentAt` in UTC.

### 2.2 Ausgang: was am Stream hinausgeht

```http
GET /stream?roomId=<UUID>
Accept: text/event-stream
Last-Event-ID: <UUID>        # optional
```

Der Endpunkt gehört dem `chat-service` und ist nur aus dem Docker-Netz `chat-net`
erreichbar (Port 8080, **nicht** veröffentlicht). Eine erfolgreiche Verbindung antwortet
mit `Content-Type: text/event-stream` und bleibt offen. Jede Nachricht ist ein Ereignis:

```text
id: 46998f49-b5aa-457a-a489-cf729adb142a
data: {"id":"46998f49-…","roomId":"3f2b1c4e-…","senderId":"anna","senderName":"Anna Muster","content":"Hallo","sentAt":"2026-11-13T07:38:47.713518425Z"}

```

| Teil | Inhalt |
|---|---|
| `id:` | die UUID der Nachricht. Der Browser merkt sie sich und schickt sie nach einem Abbruch als `Last-Event-ID` zurück |
| `data:` | das JSON mit denselben sechs Feldern und denselben Werten wie am Exchange. Eine Zeile: Jackson schreibt Zeilenumbrüche im Text als `\n`, nie als echten Umbruch, sonst würde das Ereignis zerbrechen |
| Ereignisname | keiner (`event:` fehlt) |

`id:` und `data:` gehören immer zur selben Nachricht.

Direkt nach dem Öffnen schickt der Server **eine Kommentarzeile** (`: connected`). Sie hat weder `id:` noch
`data:`. Ohne sie verschickt der Server die Antwortköpfe erst mit dem ersten Ereignis, und ein Client in einem
ruhigen Raum bekäme nie sein `200` (gemessen im Test, Plan Schritt 5).

| Anfrage | Antwort |
|---|---|
| `roomId` fehlt | **400** |
| `roomId` ist keine UUID | **400** |
| `Last-Event-ID` ist keine UUID | **400** |
| `Last-Event-ID` ist eine gültige UUID, steht aber nicht (mehr) im Puffer, oder gehört zu einem anderen Raum | **409** (siehe 4.6) |
| `Last-Event-ID` fehlt | 200, **keine** alten Nachrichten, nur neue |

---

## 3. Queues

| Name | Wem gehört sie | Lebensdauer | Grenze | Bei Erreichen der Grenze |
|---|---|---|---|---|
| Instanzqueue, Name pro Prozess neu erzeugt | genau einer `chat-service`-Instanz, mit genau einem Consumer | nicht dauerhaft, exklusiv an die Verbindung der Instanz, automatisch gelöscht | `x-max-length` = 750, dazu Vorabholmenge 250 | `x-overflow` = `drop-head`: Der Broker wirft die **älteste** wartende Nachricht dieser Queue weg |
| `chat.persist` | `batch-writer` | durable | keine | unverändert (siehe `spec-batch-writer.md`) |
| `chat.dlq` | `batch-writer` | durable | keine | unverändert |

Die Instanzqueue ist an `chat.delivery` gebunden. Ihr Name ist **keine Konstante** in
`QueueNames`: er entsteht beim Start und ist nur dieser Instanz bekannt.

Weitere Eigenschaften:

| Eigenschaft | Wert |
|---|---|
| Heartbeat | 5 Sekunden angefordert (`spring.rabbitmq.requested-heartbeat`). Spring Boot lässt den Wert sonst offen, und der Broker-Standard wäre ein Vielfaches |
| Consumer | genau einer pro Queue, ohne parallele Threads |
| Vorabholmenge (prefetch) | 250, ausdrücklich gesetzt (`spring.rabbitmq.listener.simple.prefetch`), nicht der Vorgabe überlassen |
| Zeitlimit einer SSE-Verbindung | 30 Minuten (7, Punkt 2) |
| Quittung | nach der Übergabe an den lokalen Zustand (4.1), nicht vorher |

**Warum braucht jede Instanz eine eigene Queue?** Nur eine Instanz kennt ihre eigenen
SSE-Verbindungen. Mit **einer gemeinsamen** Queue würde der Broker jede Nachricht an
genau **eine** Instanz geben (Competing Consumers, wie beim `batch-writer`). Ein Client,
der an Instanz 2 hängt, bekäme dann nichts, wenn die Nachricht bei Instanz 1 landet. Das
ist beim `batch-writer` gewollt, hier wäre es der Fehler.

**Was passiert mit der Queue, wenn die Instanz weg ist? Wer räumt auf?** Der Broker, nicht
wir. Exklusiv heisst: die Queue gehört der Verbindung. Bei geordnetem Stopp wird die
Verbindung geschlossen und die Queue sofort gelöscht. Verschwindet die Instanz ohne Stopp
(Absturz, Kill), erkennt der Broker die tote Verbindung am ausbleibenden Heartbeat und löscht
dann die Queue. Mit 5 s Heartbeat dauert das nach der Dokumentation etwa 10 s (zwei
verpasste Intervalle). Die Anforderung ist «spätestens 30 s» (S4). Der Wert ist noch nicht gemessen.

**Warum 750 und 250, und was zählt RabbitMQ dabei mit?** `x-max-length` zählt **nur bereite**
Nachrichten (`messages_ready`). Nachrichten, die der Broker schon an den Consumer
ausgeliefert hat, aber noch nicht bestätigt sind (`messages_unacknowledged`), zählen **nicht**
mit. Sie sind durch die Vorabholmenge (prefetch) begrenzt. `rabbitmqctl list_queues name messages`
zeigt bereit **plus** unbestätigt. Der Auftrag verlangt, dass die Queue «nie mehr als 1000
Nachrichten» hält, und ein Prüfskript kann dafür diese Summe lesen. Darum gilt:
`x-max-length` 750 plus Vorabholmenge 250 gleich **höchstens 1000 insgesamt**, auch bei einer
festgefahrenen Instanz. Die Vorabholmenge wird ausdrücklich gesetzt, damit die Rechnung nicht
von einer Vorgabe der Bibliothek abhängt.
Die Zahl 1000 des **Replay-Puffers** (4.6) ist davon getrennt: ein Client, der nach 50
verpassten Nachrichten zurückkommt, soll sie sicher darin finden. Mehr wäre Speicher auf Vorrat.

### 3.1 Wo es im Code landet (Vorschlag)

Alles im bestehenden Modul `chat-service`, Paket `ch.benedict.m321.chatservice`. Kein neues
Modul, kein neuer Container, keine neue Abhängigkeit: `spring-boot-starter-web` bringt
`SseEmitter` schon mit, `spring-boot-starter-amqp` den Listener.

| Baustein | Aufgabe |
|---|---|
| `config/RabbitConfig` (erweitert) | deklariert Instanzqueue und Bindung an `chat.delivery` |
| `service/LiveDeliveryListener` | der eine Consumer der Instanzqueue, reicht jede Nachricht weiter |
| `service/LiveDeliveryState` | hält Replay-Puffer, bekannte UUIDs und offene Verbindungen unter **einem** Schutz (4.7) |
| `controller/StreamController` | `GET /stream`, prüft die Eingabe und meldet 400 oder 409 |
| `application.yml` | `requested-heartbeat: 5s` |

### 3.2 Compose

Keine neuen Pflichtvariablen, keine neuen Secrets. `chat-service` hat in
`docker-compose.yml` **schon** weder `container_name` noch `ports:`, `--scale chat-service=2`
funktioniert also ohne Änderung der Datei. Alle Dienste liegen im Netz `chat-net`.

---

## 4. Verhalten

### 4.1 Normalfall: eine Nachricht von `POST` bis `data:`

1. `POST /messages` → `MessageService` vergibt `id` und `sentAt` → `MessagePublisher`
   veröffentlicht in `chat.persist` und danach an `chat.delivery`.
2. Der Fanout legt je eine Kopie in **jede** Instanzqueue, auch in die der annehmenden
   Instanz.
3. Der Listener jeder Instanz nimmt die Nachricht aus ihrer Queue und ruft `LiveDeliveryState`
   auf. Dort geschieht unter **demselben Schutz**:
   a) war die `id` schon bekannt, endet die Verarbeitung, es entsteht **kein** zweites Ereignis,
   b) sonst kommt die Nachricht in den Replay-Puffer (4.6),
   c) dann geht sie an jede offene Verbindung **desselben Raums**: ein Ereignis `id:` + `data:`.
4. Erst danach bestätigt der Listener die Lieferung (ACK).

**Warum diese Reihenfolge?** Puffer vor Verteilung: scheitert die Verteilung an einem
Client, kann der später mit `Last-Event-ID` nachholen (4.7). ACK zuletzt: eine bestätigte
Nachricht ist aus der Queue weg, und ein Absturz davor soll sie nicht verlieren.

### 4.2 Zwei Instanzen, zwei Räume (S3)

**Ausgangslage:** `--scale chat-service=2`. Client A1 hängt an Instanz 1, Client A2 an
Instanz 2, beide im Raum A. Client B hängt im Raum B. 200 Nachrichten gehen an Instanz 1,
200 an Instanz 2, alle in Raum A.

**Was geschieht:** Jede Nachricht geht an beide Instanzqueues, egal wo sie angenommen
wurde. Jede Instanz vergleicht `roomId` **lokal** mit den Räumen ihrer Verbindungen. A1
und A2 bekommen je alle 400, B bekommt nichts.

**Warum lokal gefiltert?** Ein Fanout-Exchange kennt keine Routing-Keys, und der Broker
weiss nichts von SSE-Verbindungen. Ein Filter am Broker (zum Beispiel ein Topic-Exchange
mit Raum als Schlüssel) würde pro Raum eine Bindung pro Instanz verlangen, die laufend
auf- und abgebaut werden müsste. Der lokale Vergleich kostet eine Zeile.

**Reihenfolge.** Es gilt eine **bescheidene** Zusage: Nachrichten **eines Absenders, den
eine Instanz nacheinander publiziert**, kommen in dieser Reihenfolge an. Grund: RabbitMQ
bewahrt die Reihenfolge eines Publishers innerhalb einer Queue, und die Instanz arbeitet
mit genau einem Consumer, also seriell. Nachrichten desselben Absenders, die an **zwei
verschiedenen** Instanzen angenommen werden, haben keine definierte Reihenfolge
untereinander. Der Test sendet darum pro Zielinstanz einen eigenen Absender, nacheinander,
mit laufender Nummer im Text (siehe 5, S3 und 7, Punkt 3).

### 4.3 Instanz fällt weg (S4)

**Ausgangslage:** zwei Instanzen, zwei Instanzqueues, zwei Bindungen an `chat.delivery`, je
ein Consumer.

**Was geschieht** bei `docker compose up -d --scale chat-service=1`: Docker stoppt eine
Instanz, ihre Verbindung geht zu, der Broker löscht die exklusive Queue samt Bindung. Es
bleibt eine Instanzqueue mit einem Consumer. `chat.persist` und `chat.dlq` bleiben
unberührt. **Warum:** die Queue gehört der Verbindung, nicht dem Namen. Nach einem
Absturz statt Stopp übernimmt der Heartbeat (3).

SSE-Clients der gestoppten Instanz verlieren ihre Verbindung. Sie müssen neu verbinden
(Browser-`EventSource` tut das selbst) und mit `Last-Event-ID` nachholen. Das geht gegen die
verbliebene Instanz, **solange diese die Cursor-Nachricht im Puffer hat** (7, Punkt 9).

### 4.4 `batch-writer` und Datenbank stehen (S5)

**Warum stört das den Zustellweg nicht?** Der Zustellweg berührt weder `chat.persist` noch
PostgreSQL. `MessagePublisher` sendet an beide Ziele getrennt, und für den Absender ist
nur das Senden in den Broker entscheidend. Die Instanzqueue hängt am Exchange, der
`batch-writer` an einer anderen Queue. Ein Stillstand dort füllt nur `chat.persist`.
Die Zustellung ist an **keiner** Stelle von der Datenbank abhängig, und das soll auch so
bleiben. Gerade das prüft S5.

**Nach dem Neustart** der beiden schreibt der `batch-writer` den Rückstau aus
`chat.persist` in die Tabelle (Verhalten wie `spec-batch-writer.md`, S4).

### 4.5 Instanz hängt (S6)

**Ausgangslage:** zwei Instanzen, eine wird mit `docker pause` eingefroren. Ihr Prozess
läuft nicht mehr, ihre Verbindung zum Broker bleibt vorerst offen.

**Was geschieht in der Queue:** Der Broker liefert bis zur Vorabholmenge aus (unbestätigt),
den Rest sammelt er. Bei 750 bereiten Nachrichten wirft er mit jeder neuen die älteste
weg (`drop-head`). Die Queue hält also nie mehr als 750 bereite plus 250 unbestätigte, zusammen höchstens 1000, der Broker bleibt
unbelastet, die gesunde Instanz bekommt ihre **eigene** Kopie aller Nachrichten und ist
unberührt, weil jede Instanz ihre eigene Queue hat.

**Was der Test beachten muss:** Eingefroren heisst auch: keine Heartbeats mehr. Nach etwa
10 s schliesst der Broker die Verbindung und löscht die Queue (3). Das ist kein Fehler, nimmt
aber dem Test seine Aussage über `drop-head`. **Das Zählen der Queue-Tiefe muss darum in den
ersten Sekunden nach dem Senden stattfinden.**

**Nach dem Fortsetzen** (`docker unpause`): Hat der Broker die Verbindung schon geschlossen,
baut Spring AMQP sie neu auf. `RabbitAdmin` deklariert dabei die Queue und die Bindung
erneut, der Consumer hängt sich wieder an. Neue Nachrichten kommen dann wieder an. Die
verworfenen und die in der Zwischenzeit entgangenen **bleiben verloren**, so ist es
gewollt (E3).

### 4.6 Verbindung bricht ab, Client kommt zurück (S7)

**Woher kommen die verpassten Nachrichten?** Aus dem **Replay-Puffer** der Instanz: dem
Arbeitsspeicher, nicht der Datenbank. Jede Instanz hält die **letzten 1000 verschiedenen
Nachrichten**, **global** (alle Räume zusammen) in Empfangsreihenfolge, nach UUID
duplikatsicher. Beim 1001. Eintrag fliegt der älteste raus. Der Puffer wird weder gespeichert
noch zwischen Instanzen geteilt. Da jede gesunde Instanz dieselben Nachrichten erhält,
kann ein Client zu jeder zurückkehren, **die den Cursor noch im Puffer hat**.

**Ablauf bei `Last-Event-ID`:**

1. Die Instanz sucht die UUID im Puffer.
2. Ist sie da **und** gehört die Nachricht zum angefragten Raum: Die Instanz schickt alle
   **späteren** Nachrichten **desselben Raums**, in Empfangsreihenfolge. Die Cursor-Nachricht
   selbst wird **nicht** wiederholt.
3. Danach läuft dieselbe Verbindung live weiter.

**Was, wenn die `Last-Event-ID` unbekannt ist?** Gültige UUID, aber nicht im Puffer
(verdrängt, neue Instanz, anderer Raum): **409**. Der Client muss ohne Cursor neu verbinden.
Das ist ehrlich: eine Lücke wird gemeldet und nicht still verschluckt. Eine Meldung wie
«leere Liste» wäre von «es gab nichts» nicht zu unterscheiden.

**Wie verhinderst du Lücken und Doppelte beim Wechsel auf live?** Mit **einem Schutz**:
Suchen, Replay senden und die Verbindung für neue Ereignisse **anmelden** laufen unter
demselben Schutz wie das Annehmen aus dem Listener (4.7). Dazwischen kann der Listener
keine Nachricht einschieben. Eine Nachricht, die vor der Anmeldung eintrifft, steht im Puffer
und wird im Replay gesendet. Eine, die danach eintrifft, geht live. **Keine Lücke, kein
Doppeltes**, weil es keinen Zeitpunkt gibt, an dem beides gleichzeitig wahr wäre.

### 4.7 Fehler bei einzelnen Clients, Nebenläufigkeit, Speicher

- Eine Verbindung wird bei normalem Ende, Zeitablauf oder Sendefehler aus der Liste der
  offenen Verbindungen **entfernt**. Ein fehlerhafter Client stoppt weder den Consumer noch
  die Zustellung an andere. Die Nachricht steht zuvor im Puffer (4.1) und kann nachgeholt
  werden.
- **Kein** Requeue wegen eines Clientfehlers. Der Listener bestätigt, auch wenn ein Client
  nicht erreichbar war. Unerwartete **interne** Fehler dürfen die Lieferung dagegen
  scheitern lassen. Die UUID-Prüfung macht die erneute Lieferung unschädlich (4.1, 3a).
- **Ein Schutz** (in `LiveDeliveryState`) serialisiert Anmelden, Replay, UUID-Prüfung und
  Verteilen. Es gibt keine Abstimmung **zwischen** Instanzen: RabbitMQ liefert jeder
  Instanz ihre Kopie, mehr braucht es nicht.
- Der Puffer ist auf 1000 Einträge begrenzt. Beendete und fehlerhafte Verbindungen werden
  entfernt.
- **Logs** nennen nur technische Grössen: Nachrichten-ID, Queue, Raum, Verbindungsereignis,
  Fehlerklasse. **Nie** den Inhalt (der Inhalt ist Benutzerdaten, wie schon in
  `MessageService`, das nur die Länge protokolliert).

---

## 5. Abnahmekriterien

Jede Zeile nennt den Befehl, der sie misst. Die Spalte **Messwert bleibt leer**, bis der Befehl
wirklich gelaufen ist. Ein Skript `scripts/verify-live-delivery.sh` führt die Befehle später aus
(Aufgabe im Plan). Vorbereitung: `cp .env.example .env`. Die Befehle laufen in dieser
Reihenfolge auf **demselben** Stack.

Wiederkehrende Bausteine:

```bash
# IPs der Instanzen im Netz chat-net (Docker-DNS gäbe nur irgendeine der beiden)
for container in $(docker compose ps -q chat-service); do
  docker inspect -f '{{(index .NetworkSettings.Networks "chat-net").IPAddress}}' "$container"
done

# Einen SSE-Client starten (aus einem Container im Netz, 8080 ist nicht veröffentlicht)
docker run --rm --network chat-net curlimages/curl -sN \
  -H 'Accept: text/event-stream' "http://<ip>:8080/stream?roomId=<uuid>" > client.txt

# Queues mit bereiten und unbestätigten Nachrichten
docker compose exec rabbitmq rabbitmqctl list_queues name consumers messages_ready messages_unacknowledged
```

| Szenario | Befehl | Erwarteter Wert | Messwert |
|---|---|---|---|
| **S1** | `mvn clean test` im Wurzelverzeichnis | `BUILD SUCCESS`, Exit-Code 0. Die neuen Tests laufen mit echtem RabbitMQ (Testcontainers) und in **einem** Lauf stabil, ohne dass ein Test vom anderen abhängt | 92 Tests (beide Module), 0 Fehler, `BUILD SUCCESS`, Exit-Code 0. Zweimal in je einem frischen Klon (13.11.2026) |
| **S2** | `docker compose up -d --build`, dann `docker compose ps --format '{{.Name}} {{.State}} {{.Ports}}'`, `docker compose config \| grep -c published`, `grep -c "ports" docker-compose.yml`, `rabbitmqctl list_queues name consumers` | Alle Dienste `running`. Keine Zeile mit `0.0.0.0` oder `->`. Beide `grep -c` melden `0`. **Direkt nach dem Start, ohne eine einzige Nachricht**, steht eine Instanzqueue mit `consumers` = 1 in der Liste (die Queue wird beim Start deklariert, nicht erst bei der ersten Nachricht) | 4 Dienste `running`, 0 veröffentlichte Ports (`ps`, `config`, `grep`), vor der ersten Nachricht 1 Instanzqueue mit 1 Consumer und 1 Bindung |
| **S3** | `docker compose up -d --scale chat-service=2`, IPs bestimmen (oben), drei Clients: A1 an Instanz 1, A2 an Instanz 2 (beide Raum A), B (Raum B). Dann je 200 `POST /messages` an **jede** Instanz in Raum A, nacheinander, mit eigenem `senderId` pro Zielinstanz und laufender Nummer in `content`. Danach `grep -c '^id:'` und eine Auswertung der IDs und der Nummern je Absender | A1 und A2 haben je **400** verschiedene `id:`-Zeilen, keine doppelt. Pro Absender steigen die Nummern ohne Lücke und ohne Vertauschung. B hat **0**. Die `data:`-Felder entsprechen den sechs Feldern aus 2.1 | gesendet 200+200, A1 = 400 (400 verschiedene), A2 = 400 (400 verschiedene), Reihenfolge je Absender stimmt, B = 1 (nur die eigene Kontrollnachricht) |
| **S4** | `rabbitmqctl list_queues name consumers` und `rabbitmqctl list_bindings source_name destination_name`, dann `docker compose up -d --scale chat-service=1`, danach alle 5 s wiederholen, bis die Instanzqueue weg ist | Vorher: **zwei** Instanzqueues mit je `consumers` = 1, beide an `chat.delivery` gebunden. Nachher: spätestens nach **30 s** nur noch **eine**. `chat.persist` und `chat.dlq` sind in beiden Listen unverändert vorhanden | vorher 2 Instanzqueues mit je 1 Consumer und 2 Bindungen, nach 0 bis 1 s nur noch 1, `chat.persist` und `chat.dlq` vorhanden |
| **S5** | `docker compose stop batch-writer postgres`, SSE-Client öffnen, 100 `POST /messages`, Zeit von der ersten Anfrage bis zur 100. Zeile messen und die IDs aus den Antworten merken. Dann `docker compose start postgres batch-writer`, warten, `SELECT count(*) FROM message WHERE id IN (…)` | Alle 100 `id:` im Client in **höchstens 5 s**. Während des Stillstands wächst `chat.persist` um 100. Nach dem Start steht `count` = **100** | 100 von 100 beim Client nach 0 bis 1 s, `chat.persist` +100, nach dem Neustart 100 Zeilen |
| **S6** | Zwei Instanzen, die zweite mit `docker pause <container>` einfrieren, 5000 `POST /messages` an die erste, **innerhalb von etwa 10 s** `rabbitmqctl list_queues name messages_ready messages_unacknowledged`, Client an der ersten zählt die IDs. Dann `docker unpause`, bis zu 30 s warten, Client an der zweiten öffnen, eine Kontrollnachricht senden | Die Instanzqueue der pausierten hat **höchstens 1000** Nachrichten (`messages` = bereit plus unbestätigt), davon `messages_ready` höchstens 750. Der gesunde Client hat **5000** verschiedene IDs. Nach dem Fortsetzen erhält ein Client an der zweiten Instanz die Kontrollnachricht | höchste Tiefe 1000 (`messages`), davon bereit 750, also unbestätigt 250 (Punkt 4 in Abschnitt 7), gesunder Client 5000, nach dem Fortsetzen 2 Queues und Kontrollnachricht angekommen |
| **S7** | Client verbinden, erste `id:` als Cursor speichern, Client trennen, 50 `POST /messages` in denselben Raum, 10 s warten, mit `Last-Event-ID: <Cursor>` neu verbinden, danach eine Kontrollnachricht senden. Dazu: unbekannte UUID als `Last-Event-ID` → HTTP-Status; `roomId` fehlend, keine UUID; `Last-Event-ID` keine UUID → HTTP-Status | Nach dem Neuverbinden **genau 50** Ereignisse in der gesendeten Reihenfolge, keines doppelt, der Cursor nicht darunter, dann **die Kontrollnachricht** als 51. Unbekannte, gültige UUID: **409**. `roomId` fehlt oder keine UUID, `Last-Event-ID` keine UUID: **400** | genau 50 in Reihenfolge, Cursor nicht wiederholt, danach live (gesamt 51, verschieden 51); unbekannte ID 409, `roomId` fehlt 400, keine UUID 400, `Last-Event-ID` keine UUID 400 |
| **S8** | `grep -rnE "\.stream\(\|parallelStream\|java\.util\.stream" chat-service/src`, Durchsicht der Kommentare (`scripts/check-code-rules.sh chat-service`, siehe 7, Punkt 8), `git ls-files -- .env` | Keine Treffer bei Streams. Über **jeder** neuen Klasse und **jeder** neuen Methode, auch in Tests, steht ein Kommentar. `git ls-files -- .env` gibt nichts aus | keine Streams, keine unerlaubten Lambdas, Kommentare in 27 Dateien, `git ls-files -- .env` leer (0 Zeilen) |

Dazu ein Test pro Eigenschaft, die der Abschnitt 4 behauptet (die Aufgaben stehen im Plan):
Raumfilter, doppelte UUID erzeugt kein zweites Ereignis, Replay mit Cursor, 409 bei
unbekanntem Cursor, 400 bei ungültiger Eingabe, Verbindung mit Sendefehler stoppt die
anderen nicht, Puffer-Grenze bei 1001.

---

## 6. Entscheidungen und verworfene Alternativen

| # | Entscheidung | Verworfen | Begründung |
|---|---|---|---|
| E1 | **SSE direkt im `chat-service`**, `GET /stream` | WebSocket im `web-gateway`; ein neuer Dienst | Genau dieser Endpunkt wird bewertet, Gateway und Oberfläche sind ausgeschlossen. Ein zusätzlicher Dienst bräuchte einen eigenen Container und liesse sich vom Prüfskript nicht erreichen |
| E2 | **Eine Queue pro Instanz**, nicht dauerhaft, exklusiv, auto-delete | Eine gemeinsame Queue für alle Instanzen; eine dauerhafte Queue pro Instanz | Fanout muss **jede** Instanz erreichen, weil nur sie ihre SSE-Clients kennt (3). Dauerhafte Queues blieben nach dem Skalieren zurück und füllten sich ohne Consumer. Exklusiv und auto-delete lassen den Broker aufräumen |
| E3 | **`x-max-length` 750 mit `drop-head`, Vorabholmenge 250** | `reject-publish`; unbegrenzt; eine Dead-Letter-Queue | `drop-head` wirft das Älteste weg: für Live-Anzeige ist das Neue wichtiger. `reject-publish` wirft das **Neueste** weg und schiebt den Fehler zum Absender. Unbegrenzt füllt den Broker bei einer hängenden Instanz. Eine Dead-Letter-Queue hiesse: eine verspätete Live-Nachricht aufheben. Sie hat danach keinen Wert mehr. **750 statt 1000:** `x-max-length` zählt nur bereite Nachrichten, die Vorabholmenge kommt dazu. Nur 750 plus 250 hält die Summe sicher unter dem verlangten Wert 1000. Verworfen: `x-max-length` 1000 und messen, denn ein Prüfskript, das `messages` liest, sähe bis zu 1250 |
| E4 | **Raumfilter lokal in jeder Instanz** | Topic-Exchange mit Raum als Routing-Key | Siehe 4.2. Ein Fanout kennt keine Schlüssel, der lokale Vergleich ist eine Zeile |
| E5 | **Replay-Puffer im Arbeitsspeicher**, 1000 Nachrichten global | Abfrage der Datenbank | Die Zustellung bliebe sonst an `batch-writer` und Datenbank gekoppelt: S5 verlangt das Gegenteil. Zudem sind asynchron noch nicht geschriebene Nachrichten dort gar nicht zu finden (Lücke). Für S7 reichen 50 Nachrichten sicher in 1000. Preis: siehe 7, Punkte 7 und 9 |
| E6 | **`SseEmitter` aus Spring MVC** | WebFlux, `Flux<ServerSentEvent>` | `spring-boot-starter-web` ist schon im `chat-service` (`pom.xml`). WebFlux wäre eine zweite Abhängigkeit und ein zweites Programmiermodell neben dem MVC-Controller, den die Klasse schon liest |
| E7 | **Ein Schutz** für Puffer, UUID-Prüfung und Verbindungen (`synchronized` auf `LiveDeliveryState`) | Sperrfreie Datenstrukturen; mehrere Schlösser | Nur so ist «Replay und Anmelden ohne Lücke» (4.6) in einem Satz erklärbar. Die Last einer Instanz (ein Consumer, wenige Clients) ist weit unter dem, was ein Schloss tragen kann. Preis: siehe 7, Punkt 1 |
| E8 | **Heartbeat 5 s** | Broker-Standard | Ein Absturz ohne Stopp soll die Queue binnen weniger Sekunden freigeben (S4: 30 s). Mit dem Standard wären es Minuten |
| E9 | **Ein Consumer pro Instanz, kein Parallelbetrieb** | Mehrere Threads | Ein Consumer hält die Reihenfolge einer Queue, und `consumers = 1` ist an `rabbitmqctl` ablesbar. Gleiche Begründung wie E10 im `batch-writer` |
| E10 | **UUID-Prüfung** vor der Verteilung | Dem Broker vertrauen | Eine erneute Zustellung (nach internem Fehler, 4.7) soll **kein** zweites Ereignis erzeugen. Die Prüfung kostet einen Nachschlag im Puffer |

---

## 7. Grenzen und offene Punkte

Ehrlich benannt, nicht weggeschwiegen. Die Punkte 1 und 2 sind am 13.11.2026 entschieden (Punkt 1 nach dem Review nachgebessert, siehe Plan, Eintrag 1).

| # | Punkt | Folge | Möglicher Weg |
|---|---|---|---|
| 1 | **Ein langsamer Client kann den Consumer aufhalten.** `SseEmitter.send` schreibt blockierend, und die Verteilung läuft unter dem Schutz aus E7 | Ein Client, dessen Verbindung offen, aber verstopft ist, hält Listener und alle anderen Clients der Instanz **bis zu 5 s** auf, danach bricht Tomcat das Schreiben ab (`server.tomcat.connection-timeout`), die Verbindung wird entfernt. **Gemessen am 13.11.2026:** ohne diese Einstellung hing der gesunde Client über 40 s, mit 3 s kam alles nach 3 s an. Test `StalledClientIntegrationTest` | Senden ausserhalb des Schutzes würde Reihenfolge und «keine Lücke» aus 4.6 brechen. Eine Warteschlange pro Client wäre eine neue Abstraktion für ein Problem, das der Timeout begrenzt |
| 2 | **Zeitlimit der Verbindung nicht festgelegt.** Spring MVC beendet asynchrone Anfragen nach der Vorgabe des Servers (30 s bei Tomcat) | Ohne Eingriff trennt der Server jeden Client alle 30 s. S6 dauert länger | **Entschieden: 30 Minuten**, am `SseEmitter` gesetzt (`new SseEmitter(1_800_000L)`). Nach Ablauf verbindet der Client mit `Last-Event-ID` neu (4.6). Deckt S3 bis S7 mit grossem Abstand |
| 3 | **Reihenfolge nur für nacheinander publizierende Absender.** Der Broker garantiert sie pro Channel. `RabbitTemplate` kann aufeinanderfolgende Anfragen über verschiedene Channels senden | Für einen Absender, der Anfragen **nacheinander** sendet, ist es in der Praxis geordnet. Eine Garantie ist es nicht | S3 misst es. Zeigt sich Vertauschung, einen Channel pro Instanz festlegen |
| 4 | **`messages` zählt bereit plus unbestätigt** | `x-max-length` begrenzt nur «bereit». **Gelöst durch 750 plus 250** (3, E3), sofern die Vorabholmenge wirklich 250 ist | S6 misst beide Zahlen. Zeigt `messages_unacknowledged` mehr als 250, stimmt die Einstellung nicht |
| 5 | **Eine pausierte Instanz verliert nach etwa 10 s ihre Queue** (Heartbeat) | Der Test über `drop-head` muss in den ersten Sekunden messen (4.5) | Im Skript die Messung unmittelbar nach dem Senden machen |
| 6 | **Keine Lebenszeichen auf offenen Verbindungen** | Eine tote Verbindung wird erst beim nächsten Schreiben erkannt. Bis dahin liegt sie in der Liste. Bei seltenen Nachrichten kann das lange dauern | Kommentarzeile (`: ping`) im Abstand von 15 bis 30 s. Nicht Teil dieser Aufgabe |
| 7 | **Der Puffer ist global, nicht pro Raum** | Unter Last aus anderen Räumen (S6: 5000) wird der Cursor eines stillen Raums schnell verdrängt. Der Client bekommt **409** und hat Nachrichten verloren, obwohl sein Raum ruhig war | Puffer pro Raum. Verworfen, weil die Aufgabe «die letzten 1000» global nennt |
| 8 | **`scripts/check-code-rules.sh` passt nicht auf `chat-service`** | Das Skript verbietet schon das Wort «stream» (auch in `text/event-stream`) und Lambdas. Beides trifft hier zu. Der Auftrag verbietet aber nur **Streams** (S8, enger Befehl) | Skript für `chat-service` anpassen, oder S8 mit dem engen Befehl messen. **Lambdas:** `SseEmitter` nimmt für Ende, Zeitablauf und Fehler je einen `Runnable`. **Entschieden: Lambdas sind genau dort erlaubt**, nämlich bei `onCompletion`, `onTimeout` und `onError` am `SseEmitter`, je mit einem Kommentar darüber. Sonst nirgends (CLAUDE.md verbietet Lambdas nicht, nur Streams) |
| 9 | **Neue Instanz hat einen leeren Puffer** | Ein Client, der nach einem Neustart oder Skalieren zu einer **frisch gestarteten** Instanz wechselt, bekommt 409, auch wenn sein Cursor nur Sekunden alt ist. «Jede gesunde Instanz» heisst: jede, die lange genug lief | Ehrlich so lassen. Der Client verbindet ohne Cursor neu |
| 10 | **Kein Exactly-once, keine unbegrenzte Wiederherstellung** | Bei einem Absturz der Instanz sind Puffer und Verbindungen weg. Nachrichten, die in dieser Zeit am Exchange eintrafen und in keiner Queue lagen, sind für diese Instanz verloren | Die Datenbank ist die dauerhafte Quelle (`batch-writer`). Nicht Teil dieser Aufgabe |
| 11 | **Fällt RabbitMQ aus, geht gar nichts** | Weder `POST` noch Zustellung gelingen (`MessageExceptionHandler` meldet 503). Nach der Rückkehr deklariert jede Instanz Queue und Bindung neu. Verbindungen der Clients bleiben offen, aber bis dahin kommt nichts | Wie beim `batch-writer`: Standardverhalten von Spring AMQP, nichts geändert |
| 12 | **`QueueNames` nennt noch «web-gateway-Instanzen»** | Der Kommentar bei `DELIVERY_EXCHANGE` stimmt nach dieser Aufgabe nicht mehr | Beim Bauen nachführen: «jede Instanz, die Clients bedient» |

Der endgültige Testbericht darf bestandene Szenarien erst nach einem **tatsächlich
ausgeführten** Lauf behaupten.
