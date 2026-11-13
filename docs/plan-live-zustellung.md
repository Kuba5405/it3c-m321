# Live-Zustellung — Umsetzungsplan

**Ziel:** Der `chat-service` stellt jede angenommene Nachricht per SSE (`GET /stream?roomId=…`) an alle
verbundenen Clients desselben Raums zu. Jede Instanz hat eine eigene Queue am Exchange `chat.delivery`,
einen begrenzten Replay-Puffer für `Last-Event-ID` und hängt an keiner Stelle an Datenbank oder `batch-writer`.

**Bezug:** Schritt 7 der Umsetzungsreihenfolge in [`PLANUNG.md`](../PLANUNG.md) (Abschnitt 2.2, Schritte 4 und 5).
**Spezifikation:** [`spec-live-zustellung.md`](spec-live-zustellung.md). Sie steht im Git-Log **vor** diesem Plan.
**Vorbild:** [`plan-batch-writer.md`](plan-batch-writer.md).

Dieser Plan ist das Drehbuch für die KI. Sie schreibt den Code, Schritt für Schritt nach diesem Plan. Pro
Schritt gibt es einen Auftrag, ich lese, was sie liefert, lasse den Test laufen und korrigiere, **bevor** ich committe.

## Spielregeln für diesen Plan

- **Vor Schritt 1:** Spezifikation committen, dann Plan committen, dann **pushen**. Erst danach beginnt Schritt 1.
- **Ab dem ersten Push ändert sich am bestehenden Plan nichts mehr.** Muss ich doch umplanen, bleibt der alte
  Text stehen, und die Änderung kommt als eigener Eintrag mit **Änderungsgrund** in den Abschnitt
  «Änderungen am Plan», in einem eigenen Commit. Die einzige Ausnahme ist das Feld **«Geprüft und korrigiert»**.
- **Ein Commit pro Schritt.** Jeder Commit der KI endet mit
  `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`.

## Globale Vorgaben

Diese Punkte gelten für **jeden** Auftrag und stehen darum nicht in jedem einzeln:

- Java 21, Spring Boot 3.5.16, Spring MVC (`SseEmitter`), Spring AMQP, RabbitMQ 3.13. **Keine neue Abhängigkeit**, kein WebFlux.
- Alles im bestehenden Modul `chat-service`, Paket `ch.benedict.m321.chatservice`. Kein neues Modul, kein neuer Container.
- **Code auf Englisch**, Kommentare, Javadoc, Commit-Messages und Doku **auf Deutsch** (`CLAUDE.md`).
- **Keine verschachtelten Aufrufe**, ein Ergebnis pro Zeile in einer benannten Variable. Gilt auch in Tests.
- **Keine Streams.** Lambdas nur bei `onCompletion`, `onTimeout`, `onError` am `SseEmitter`, je mit Kommentar darüber.
- **Über jeder Klasse und jeder Methode ein Kommentar**, der erklärt, *warum* es sie gibt. Auch bei Tests.
- Lombok nur für `@Slf4j` und `@RequiredArgsConstructor`, Java-`record` für Datenklassen.
- **Nie den Nachrichteninhalt loggen.**
- **Kein `ports:`-Eintrag** in `docker-compose.yml`, keine Geheimnisse im Repository.
- **Test vor Code**: erst der Test, der fehlschlägt, dann der Code, der ihn grün macht.
- Voraussetzung: Docker läuft (Testcontainers).

## Reihenfolge im Überblick

| # | Schritt | Commit-Message | Szenario |
|---|---|---|---|
| 1 | Instanzqueue und Heartbeat | `feat: Eigene Queue pro Instanz an chat.delivery binden` | S2, S4 |
| 2 | Replay-Puffer | `feat: Replay-Puffer mit UUID-Prüfung für die Live-Zustellung` | S7 |
| 3 | Verbindungen und Verteilung | `feat: Nachrichten an Verbindungen desselben Raums verteilen` | S3, S7 |
| 4 | Listener für die Instanzqueue | `feat: Instanzqueue lesen und an die Verbindungen weitergeben` | S3, S5 |
| 5 | Endpunkt `GET /stream` mit Neuverbindung | `feat: SSE-Endpunkt GET /stream mit Last-Event-ID` | S3, S5, S7 |
| 6 | Test mit zwei Instanzen | `test: Zwei Instanzen stellen jede Nachricht beiden Clients zu` | S3 |
| 7 | Code-Regeln für `chat-service` prüfbar | `test: Regelprüfung für den chat-service anpassen` | S8 |
| 8 | Abnahmeskript | `test: Abnahmeskript für die Live-Zustellung` | S2 bis S7 |
| 9 | README und Stand-Tabelle | `docs: README um die Live-Zustellung ergänzen` | – |
| 10 | Abnahme und Messwerte | `docs: Messwerte der Live-Zustellung nachtragen` | S1 bis S8 |

**Warum diese Reihenfolge:** von unten nach oben. Zuerst das, was nur den Broker braucht (1), dann reine Logik ohne
Broker und HTTP (2, 3), dann die Verbindung dazwischen (4), dann das Äusserste, der Endpunkt (5). Erst wenn ein
einzelner Pfad steht, kommt der Test mit zwei Instanzen (6). Messen und Aufräumen zuletzt (7 bis 10).

---

## Schritt 1: Instanzqueue und Heartbeat

**Ziel:** Jede `chat-service`-Instanz deklariert beim Start eine eigene, nicht dauerhafte, exklusive, auto-delete-Queue
mit `x-max-length` 750 und `x-overflow` `drop-head`, gebunden an `chat.delivery`. Heartbeat 5 s, Vorabholmenge 250
(750 plus 250 gleich höchstens 1000 Nachrichten in der Queue).

**Warum an dieser Stelle:** Ohne eigene Queue am Exchange gibt es nichts zu lesen. Die Eigenschaften der Queue tragen
S4 und S6. Falsch deklariert, merkt man es sonst erst bei einer hängenden Instanz.

**Auftrag an die KI:**
> Lies `CLAUDE.md`, `docs/spec-live-zustellung.md` (Abschnitt 3) und `chat-service/.../config/RabbitConfig.java`.
> Schreibe zuerst einen Integrationstest in `RabbitConfigIntegrationTest` (echter RabbitMQ per Testcontainers):
> Nach dem Start existiert eine Queue, die nicht durable, exklusiv und auto-delete ist, `x-max-length` 750 und
> `x-overflow` `drop-head` hat und an `chat.delivery` gebunden ist; eine am Exchange veröffentlichte Nachricht landet
> darin. Prüfe die Eigenschaften mit `rabbitmqctl list_queues` und `list_bindings` im Container. Zeig mir den
> fehlschlagenden Test. Dann ergänze in `RabbitConfig` die Queue (Name `chat.live.` plus UUID, beim Start erzeugt,
> **keine** Konstante in `QueueNames`) und die Bindung, setze in `application.yml`
> `spring.rabbitmq.requested-heartbeat: 5s` und `spring.rabbitmq.listener.simple.prefetch: 250` und führe den Kommentar bei `QueueNames.DELIVERY_EXCHANGE` nach
> («jede Instanz, die Clients bedient», nicht mehr «web-gateway»). Nichts committen.

**Test:** `RabbitConfigIntegrationTest` (erweitert). **Zuerst rot**, dann grün.

**Geprüft und korrigiert:** Test lief zuerst rot (7 von 9 Tests, Heartbeat war 60 statt 5). Zwei Korrekturen am
Test selbst: (1) Spring deklariert die Queue erst bei der ersten Verbindung, beim Start öffnet noch nichts eine, darum
holt `rabbitAdmin.initialize()` das bis zum Listener (Schritt 4) nach; S2 («Queue vor der ersten Nachricht») ist damit
erst in Schritt 4 echt prüfbar. (2) Das Senden ist asynchron, der Test fragt bis zu 3 s nach, statt einmal zu früh.
Am Code nichts geändert. Nach dem Schritt: alle 21 Tests des `chat-service` grün.

---

## Schritt 2: Replay-Puffer

**Ziel:** Eine Klasse `ReplayBuffer` hält die letzten 1000 verschiedenen Nachrichten global in Empfangsreihenfolge,
duplikatsicher nach UUID, und liefert «alles nach diesem Cursor in diesem Raum».

**Warum an dieser Stelle:** Reine Logik ohne Broker und HTTP, also die schnellsten Tests. Die Fragen «Ist die UUID
bekannt?» und «Was kam nach dem Cursor?» müssen beantwortet sein, bevor etwas verteilt wird.

**Auftrag an die KI:**
> Lies `CLAUDE.md` und `docs/spec-live-zustellung.md` Abschnitt 4.6. Schreibe zuerst `ReplayBufferTest` (reiner
> Einheitstest, ohne Spring und ohne Container): Reihenfolge bleibt erhalten; dieselbe UUID zweimal wird einmal
> gespeichert und `add` meldet «schon bekannt»; beim 1001. Eintrag fliegt der älteste raus; `messagesAfter(cursor,
> roomId)` liefert nur spätere Nachrichten desselben Raums und nicht den Cursor selbst; unbekannter, verdrängter und
> raumfremder Cursor sind «nicht gefunden». Zeig den fehlschlagenden Test. Dann schreibe `ReplayBuffer` in
> `service/`, **nicht** thread-sicher (den Schutz baut Schritt 3), ohne Streams, mit `for`-Schleifen. Nichts committen.

**Test:** `ReplayBufferTest`. **Zuerst rot**, dann grün.

**Geprüft und korrigiert:** Test lief zuerst rot (Übersetzungsfehler, `ReplayBuffer` fehlte), danach 6 Tests grün.
Am Vorschlag nichts geändert. Gelesen und für richtig befunden: `LinkedHashMap` (Reihenfolge und schneller Nachschlag),
`add` meldet `false` bei bekannter UUID, ältester Eintrag wird über den Iterator entfernt, keine Streams.
Eine Eigenheit bewusst belassen: `messagesAfter` liefert bei unbekanntem Cursor eine leere Liste. Den Unterschied
zu «nichts verpasst» macht `knowsCursor`, das der Endpunkt vorher fragt (409).

---

## Schritt 3: Verbindungen und Verteilung

**Ziel:** `LiveDeliveryState` verwaltet offene Verbindungen und verteilt jede neue Nachricht an alle Verbindungen
desselben Raums. Anmelden mit Replay, UUID-Prüfung und Verteilen laufen unter **einem** Schutz.

**Warum an dieser Stelle:** Das heikelste Stück (Spezifikation 4.6, E7): Replay und Anmelden ohne Lücke und ohne
Doppeltes. Ohne Broker und ohne HTTP lässt es sich am genauesten testen.

**Auftrag an die KI:**
> Lies `CLAUDE.md`, `docs/spec-live-zustellung.md` (4.1, 4.2, 4.6, 4.7) und `ReplayBuffer`. Schreibe zuerst
> `LiveDeliveryStateTest` (Einheitstest, ohne Container) mit einem eigenen `SseEmitter` als Unterklasse, das die
> gesendeten Ereignisse mitschreibt und auf Wunsch `IOException` wirft, **kein Mock**: zwei Verbindungen in Raum A und
> eine in Raum B, eine Nachricht für A erreicht genau die zwei; dieselbe UUID zweimal ergibt ein Ereignis; Anmelden mit
> Cursor liefert den Replay der späteren Nachrichten und danach live; **zwei Threads**, die gleichzeitig anmelden und
> verteilen, 1000 Durchläufe, ohne Lücke und ohne Doppeltes; eine Verbindung, die beim Senden wirft, wird entfernt,
> die andere bekommt die Nachricht trotzdem, und sie steht im Puffer; ein unbekannter Cursor meldet das und meldet
> **nichts** an. Zeig den fehlschlagenden Test. Dann schreibe `LiveDeliveryState` mit `synchronized` (ein Schutz).
> Lambdas nur bei `onCompletion`, `onTimeout`, `onError`. Nichts committen.

**Test:** `LiveDeliveryStateTest`. **Zuerst rot**, dann grün.

**Geprüft und korrigiert:** Test lief zuerst rot (Übersetzungsfehler, `LiveDeliveryState` fehlte). Danach liefen 4 von
6 Tests rot, weil der Test-Emitter die ID falsch auslas: der Text lautet `id:<uuid>\ndata:`, der Test las bis zum
Ende statt bis zum Zeilenumbruch. **Fehler im Test, nicht im Code**, im Test korrigiert. Danach 6 Tests grün, auch die
1000 Durchläufe mit zwei Threads. Am Code gelesen und für richtig befunden: ein `synchronized`-Schloss für `deliver`,
`subscribe` und das Entfernen; erst in den Puffer, dann senden; Sendefehler entfernt nur die eine Verbindung. Lambdas
nur in `registerCleanup` bei `onCompletion`, `onTimeout`, `onError`. Bekannte Grenze: `send` blockiert unter dem
Schloss (Spezifikation 7, Punkt 1).

---

## Schritt 4: Listener für die Instanzqueue

**Ziel:** Ein Consumer pro Instanz liest die Instanzqueue und übergibt jede Nachricht an `LiveDeliveryState`. Bestätigt
wird nach der Übergabe, ein Clientfehler führt nicht zu Requeue.

**Warum an dieser Stelle:** Er verbindet Broker und Zustand. Hier entscheidet sich, **wann** bestätigt wird.

**Auftrag an die KI:**
> Lies `CLAUDE.md`, `docs/spec-live-zustellung.md` (3, 4.1, 4.7). Schreibe zuerst
> `LiveDeliveryListenerIntegrationTest` (echter RabbitMQ): eine am Exchange veröffentlichte Nachricht kommt im
> `LiveDeliveryState` an; dieselbe UUID zweimal ergibt ein Ereignis; die Instanzqueue hat `consumers` = 1; wirft eine
> Verbindung beim Senden, bleibt die Queue leer; zwei Nachrichten desselben Absenders bleiben in Reihenfolge. Zeig den
> fehlschlagenden Test. Dann schreibe `LiveDeliveryListener` mit `@RabbitListener` auf die Queue aus Schritt 1,
> genau ein Consumer, ohne Nachrichteninhalt im Log. Nichts committen.

**Test:** `LiveDeliveryListenerIntegrationTest`. **Zuerst rot**, dann grün.

**Geprüft und korrigiert:** Test lief zuerst rot (alle 5, `consumers` = 0, kein Ereignis). Danach alle 5 grün, am
Listener nichts geändert. Gelesen und für richtig befunden: genau ein Consumer (Spring-Standard), ACK nach dem Ende der
Methode, `#{liveQueue.name}` liest den Namen aus der Bean. **Nebenwirkung auf Schritt 1:** der dortige Test
`messageSentToTheExchangeArrivesInTheLiveQueue` ging rot, weil der Listener die Nachricht jetzt aus der Queue holt.
Seine Aussage deckt `messagePublishedToTheExchangeReachesTheConnection` ab, darum habe ich ihn samt Hilfsmethode
entfernt. Damit ist S2 («Queue vor der ersten Nachricht, ein Consumer») ohne `initialize()` geprüft
(`liveQueueHasExactlyOneConsumer`). Alle 37 Tests des `chat-service` grün.

---

## Schritt 5: Endpunkt `GET /stream` mit Neuverbindung

**Ziel:** `GET /stream?roomId=…` antwortet mit `text/event-stream`, meldet 400 und 409 laut Spezifikation 2.2 und
holt mit `Last-Event-ID` genau die verpassten Nachrichten nach. Zeitlimit der Verbindung 30 Minuten.

**Warum an dieser Stelle:** Erst wenn alles darunter steht, hat der Endpunkt nur noch Eingabe zu prüfen und einen
Emitter zu übergeben.

**Auftrag an die KI:**
> Lies `CLAUDE.md`, `docs/spec-live-zustellung.md` (2.2, 4.6, 7 Punkt 2) und `MessageController`. Schreibe zuerst
> `StreamControllerIntegrationTest` (echter Server auf zufälligem Port, echter RabbitMQ, ein Java-`HttpClient`):
> `roomId` fehlt, `roomId` keine UUID, `Last-Event-ID` keine UUID → 400; gültige, aber unbekannte `Last-Event-ID` →
> 409; ohne Cursor → 200 und `Content-Type: text/event-stream`; Ende zu Ende: `POST /messages`, dann `id:` gleich
> `data.id`, alle sechs Felder; **Neuverbindungstest `reconnectDeliversExactlyTheMissedMessages`**: Client liest eine
> Nachricht, trennt, 50 Nachrichten werden gesendet, Neuverbindung mit `Last-Event-ID` liefert genau diese 50 in
> Reihenfolge, keine doppelt, danach kommt eine neue live; ein Client in einem anderen Raum bekommt nichts. Zeig den
> fehlschlagenden Test. Dann schreibe `StreamController` mit `new SseEmitter(1_800_000L)`. Nichts committen.

**Test:** `StreamControllerIntegrationTest`. **Zuerst rot**, dann grün.

**Geprüft und korrigiert:** Test lief zuerst rot (7 Fehler, 1 Error, überall `404`). Beim ersten Lauf mit dem
Controller **hing** der Test «Verbindung ohne Cursor»: Der Server verschickt die Antwortköpfe erst mit dem ersten
Ereignis, ein Client in einem ruhigen Raum bekam also nie ein `200`. **Das war ein Fehler im Vorschlag**, nicht im
Test. Korrektur: der Controller schickt als Erstes die Kommentarzeile `: connected` (kein `id:`, kein `data:`, der
Vertrag bleibt gleich). Dazu im Test-Client ein Zeitlimit von 10 s, damit ein Hänger künftig als Fehler endet. In
der Spezifikation 2.2 nachgetragen. Danach alle 45 Tests des `chat-service` grün, darunter
`reconnectDeliversExactlyTheMissedMessages`. Gelesen: 400 für fehlende oder ungültige `roomId` und ungültige
`Last-Event-ID`, 409 für unbekannten Cursor, Zeitlimit 30 Minuten.

---

## Schritt 6: Test mit zwei Instanzen

**Ziel:** Ein Test beweist, dass bei **zwei** Instanzen beide Clients desselben Raums jede Nachricht genau einmal
bekommen, egal an welcher Instanz sie angenommen wurde, und ein Client eines anderen Raums nichts.

**Warum an dieser Stelle:** Erst jetzt, wo ein einzelner Pfad steht, lohnt sich der Test, der S3 im Kleinen nachbildet.
Er ist Teil D der Bewertung.

**Auftrag an die KI:**
> Lies `CLAUDE.md` und `docs/spec-live-zustellung.md` (4.2). Schreibe `TwoInstancesIntegrationTest`: **ein** echter
> RabbitMQ (Testcontainers), **zwei** Spring-Anwendungen im selben Test (zwei `SpringApplicationBuilder`, je zufälliger
> Port, je eigene Instanzqueue). Client A1 an Instanz 1, A2 an Instanz 2 (Raum A), Client B (Raum B). Je 20 `POST`
> an jede Instanz in Raum A, mit eigenem `senderId` pro Zielinstanz und laufender Nummer. Erwartung: A1 und A2 haben
> je 40 verschiedene Ereignisse, die Nummern pro Absender steigen ohne Lücke, B hat 0. Zeig den Test zuerst gegen einen
> absichtlich kaputten Stand (eine gemeinsame Queue statt einer pro Instanz), er muss rot werden. Dann grün. Kein
> neuer Produktivcode, falls der Test schon grün ist, sag das ausdrücklich. Nichts committen.

**Test:** `TwoInstancesIntegrationTest`.

**Geprüft und korrigiert:** Es gab keinen Produktivcode zu schreiben: der Test war gegen den Stand aus Schritt 1 bis 5
sofort grün. Das ist **kein** Beweis, dass er etwas prüft. Darum zuerst gegen einen absichtlich kaputten Stand (eine
gemeinsame, nicht exklusive Queue statt einer pro Instanz, danach zurückgesetzt): rot, jeder Client bekam 20 statt 40.
Zwei Korrekturen am Vorschlag der KI: (1) Der erste Lauf scheiterte mit «Port 8080 already in use» und «Connection
refused», weil `SpringApplicationBuilder.properties()` hinter `application.yml` zurückstehen. Port und Broker kommen
jetzt als Kommandozeilen-Argumente. (2) Der erste Lauf gegen den kaputten Stand war wegen dieses Fehlers rot, aus dem
falschen Grund. Erst nach der Korrektur zeigte er die richtige Ursache. Danach alle 46 Tests des `chat-service` grün.

---

## Schritt 7: Code-Regeln für `chat-service` prüfbar

**Ziel:** `scripts/check-code-rules.sh chat-service` misst S8 mit der Regel aus dem Auftrag: keine Streams. Das
Verhalten für `batch-writer` bleibt unverändert.

**Warum an dieser Stelle:** Der Code steht, S8 lässt sich messen. Das bestehende Skript verbietet das Wort «stream»
(auch in `text/event-stream`) und alle Lambdas und würde hier fälschlich rot (Spezifikation 7, Punkt 8).

**Auftrag an die KI:**
> Lies `scripts/check-code-rules.sh` und `docs/spec-live-zustellung.md` (7, Punkt 8). Zeig zuerst, dass
> `scripts/check-code-rules.sh chat-service` heute `FAIL` meldet und warum. Ändere das Skript so, dass für jeden Ordner
> ausser `batch-writer` nur `.stream(`, `parallelStream` und `java.util.stream` verboten sind und Lambdas nur in
> `onCompletion`, `onTimeout`, `onError` stehen dürfen. Teil 2 (Kommentare) und Teil 3 (`.env`) bleiben gleich, und
> für `batch-writer` ändert sich nichts. Beweise es: `batch-writer` → `PASS`, `chat-service` → `PASS`, ein absichtlich
> eingebautes `list.stream()` → `FAIL`, danach wieder entfernen. Nichts committen.

**Test:** die drei Läufe aus dem Auftrag.

**Geprüft und korrigiert:** Vorher gezeigt: `scripts/check-code-rules.sh chat-service` meldet `FAIL` für das Wort
«stream» (`text/event-stream`, `StreamController`), für die drei Lambdas und für Kommentare. **Der Auftrag der KI
war zu eng gefasst:** er sah nur Streams und Lambdas vor. Der Lauf zeigte, dass auch **14 ältere Methoden aus
Bewertung 1** (Hauptklasse, Fehlerbehandlung, alte Tests) keinen Kommentar hatten, und S8 verlangt ihn über jeder
Methode des `chat-service`. Ich habe sie ergänzt. Zwei Fehlgriffe der KI korrigiert: die Liste der Kommentare war
unvollständig (drei Tests in `MessageServiceTest` und eine Hilfsmethode `publish` fehlten, der erste Lauf brach mit
`KeyError` ab). Ergebnis: `chat-service` und `batch-writer` beide `PASS`, ein absichtliches `l.stream()` in
`chat-service` meldet `FAIL` (danach gelöscht). Die Tests laufen noch (nur Kommentare geändert, übersetzt).

---

## Schritt 8: Abnahmeskript

**Ziel:** `scripts/verify-live-delivery.sh` führt S2 bis S7 gegen einen echten Stack aus und meldet je `PASS` oder `FAIL`.

**Warum an dieser Stelle:** Die Szenarien sind Befehle gegen einen echten Stack (Spezifikation 5). Ein Skript führt sie
immer gleich aus.

**Auftrag an die KI:**
> Lies `scripts/verify-batch-writer.sh` und `docs/spec-live-zustellung.md` Abschnitt 5 und 7 (Punkte 3 bis 5). Schreibe
> `scripts/verify-live-delivery.sh` im selben Aufbau (`pass`, `fail`, ein Block pro Szenario, Exit-Code 1 bei `FAIL`).
> Messe: S2 (keine Ports, Instanzqueue mit einem Consumer **vor** der ersten Nachricht); S3 (IPs, drei Clients, je 200
> `POST` an beide Instanzen, ein `senderId` pro Zielinstanz); S4 (Queues und Bindungen vor und nach
> `--scale chat-service=1`, höchstens 30 s); S5 (`batch-writer` und `postgres` gestoppt, 100 Nachrichten in höchstens
> 5 s, nach dem Start 100 Zeilen); S6 (`docker pause` auf einer Instanz, 5000 Nachrichten, `messages` und `messages_ready` **innerhalb
> von etwa 10 s**, danach `unpause` und Kontrollnachricht); S7 (Cursor, 50 Nachrichten, 10 s warten, Neuverbindung,
> Kontrollnachricht, dazu 400 und 409). Behaupte nichts, was das Skript nicht gemessen hat. Nichts committen.

**Test:** Skript gegen den laufenden Stack, und einmal gegen einen absichtlich kaputten (ohne `x-max-length`): S6 muss rot werden.

**Geprüft und korrigiert:** Lauf gegen den richtigen Stand: S2 bis S7 `PASS`. Gegen einen absichtlich kaputten Stand
(Queue ohne `x-max-length`, danach zurückgesetzt): **nur S6 rot** («Tiefe=5000, erlaubt 1000»), alle anderen weiter
grün. Das zeigt, dass das Skript S6 wirklich misst. Ein Fehler im Vorschlag der KI fiel beim Lesen auf, noch vor dem
Lauf: ein Test, der kein Limit erreicht, würde bestehen, ohne etwas zu beweisen. Darum verlangt S6 zusätzlich
`bereit` von mindestens 100. Gelesen und geprüft: Messung von S6 in den ersten 8 Sekunden (Heartbeat, Spezifikation
4.5), je Zielinstanz eine eigene IP, ein Absender pro Instanz in S3. Bekannte Grenze: die Zeitmessung in S5 hat
Sekundengenauigkeit.

---

## Schritt 9: README und Stand-Tabelle

**Ziel:** Die README-Tabelle «Stand» und die Dokumentenliste kennen die Live-Zustellung. Neue Variablen in
`.env.example` nur, falls es welche gibt (laut Spezifikation 3.2: keine).

**Warum an dieser Stelle:** Wer das Projekt öffnet, soll lesen, dass es den Endpunkt gibt, dass er nur im Docker-Netz
erreichbar ist und wie man ihn prüft.

**Auftrag an die KI:**
> Lies `README.md`. Ergänze in der Tabelle «Was gebaut wird» die Live-Zustellung als Zeile beim `chat-service`
> (Stand «vorhanden»), in «Dokumente» die Spezifikation und den Plan, und einen kurzen Abschnitt mit
> `docker compose up -d --scale chat-service=2` und `scripts/verify-live-delivery.sh`. `.env.example` nur ändern, wenn
> der Code eine neue Variable braucht, und sag, ob das der Fall ist. Nichts committen.

**Test:** Die Befehle im README laufen unverändert.

**Geprüft und korrigiert:** Die Befehle im README sind dieselben, die das Skript benutzt (curl im Netz `chat-net`,
`docker compose up -d --scale chat-service=2`, `scripts/verify-live-delivery.sh`). `.env.example` blieb unverändert:
der Code braucht keine neue Variable (Spezifikation 3.2). Die Zeile `chat-service` der Tabelle «Stand» bleibt
«vorhanden» und nennt jetzt SSE. Spezifikation und Plan stehen in der Dokumentenliste.

---

## Schritt 10: Abnahme und Messwerte

**Ziel:** Alle acht Szenarien laufen in dieser Reihenfolge auf demselben Stack aus dem Fork, mit `.env.example` als
einziger Vorlage. Die Spalte «Messwert» der Spezifikation ist gefüllt.

**Warum an dieser Stelle:** Erst jetzt gibt es etwas zu messen. «Bei mir geht es» zählt nicht.

**Auftrag an die KI:**
> Führe in einem frischen Klon mit `cp .env.example .env` aus: `mvn clean test`, `scripts/verify-live-delivery.sh`,
> `scripts/check-code-rules.sh chat-service`, `scripts/verify-batch-writer.sh`. Trage die **gemessenen** Werte in die
> Spalte «Messwert» von `docs/spec-live-zustellung.md` ein, ebenso `messages_unacknowledged` aus S6 (7, Punkt 4). Bei einem nicht
> bestandenen Szenario: nichts schönreden, nenne den Messwert und stopp. Nichts committen.

**Test:** die Abschluss-Prüfung unten.

**Geprüft und korrigiert:** _(nach dem Schritt ausfüllen)_

---

## Abschluss-Prüfung

- [ ] `mvn clean test` im Wurzelverzeichnis: alles grün, in einem Lauf (S1)
- [ ] `scripts/verify-live-delivery.sh`: S2 bis S7 `PASS`
- [ ] `scripts/check-code-rules.sh chat-service` und `… batch-writer`: S8 `PASS`
- [ ] `scripts/verify-batch-writer.sh` weiterhin `PASS`
- [ ] `docker compose config | grep -c published` meldet `0`
- [ ] `git status --short` sauber, `git ls-files -- .env` ohne Ausgabe
- [ ] `git tag bewertung-2 && git push origin bewertung-2`

---

## Risiken beim Bauen

| Risiko | Woran man es merkt | Was tun |
|---|---|---|
| Reihenfolge bei S3 vertauscht (Spezifikation 7, Punkt 3) | Nummern eines Absenders springen | Änderung am Plan mit Grund, einen Channel pro Instanz festlegen |
| S6 zeigt über 1000 | Die Vorabholmenge ist nicht 250, oder `x-max-length` nicht 750 | Spezifikation 3 und 7, Punkt 4: beide Werte nachlesen, Einstellung prüfen |
| S6 findet die Queue nicht | Mehr als etwa 10 s nach dem Pausieren gemessen | Direkt nach dem Senden messen |
| Test hängt ohne Fehlermeldung | Ein Client liest nicht, der Listener blockiert (Punkt 1) | Bekannte Grenze, im Test Clients immer leerlesen |

---

## Änderungen am Plan

Leer. Ab dem ersten Push steht hier jede Änderung mit **Datum, altem Text, neuem Text und Änderungsgrund**, je in
einem eigenen Commit.
