# M321 — Chat-App (Klasse IT3c)

Lernprojekt zum Modul **M321 Verteilte Systeme / Microservices**. Wir bauen gemeinsam eine
Chat-Anwendung aus mehreren Services, die über eine Message Queue miteinander reden und mit
docker-compose gestartet werden.

## Für Lernende: so startest du

1. Dieses Repository **forken** (Button «Fork» oben rechts).
2. Deinen Fork klonen:
   ```bash
   git clone https://github.com/<dein-benutzername>/it3c-m321.git
   cd it3c-m321
   ```
3. Voraussetzungen installieren: **Java 21**, **Maven**, **Docker Desktop**, **Git**.
4. Lokale Umgebungsdatei anlegen und die Werte anpassen:
   ```bash
   cp .env.example .env
   ```
5. Die Planung lesen (siehe unten) — erst verstehen, dann programmieren.

Alle Aufgaben werden in **deinem Fork** gelöst. Das Original-Repository bleibt die Referenz.

## Bauen, testen, starten

```bash
mvn clean test                   # alle Tests beider Dienste, in einem Lauf
docker compose up -d --build     # RabbitMQ, PostgreSQL, chat-service und batch-writer im Netz chat-net
```

Für `mvn test` muss **Docker laufen**: RabbitMQ und PostgreSQL starten dabei per Testcontainers
als echte Container. Kein Dienst veröffentlicht einen **Port** auf den Host. Der einzige offene
Port des Gesamtsystems gehört später dem Gateway.

### Eine Nachricht von Hand durch das System schicken

Weil kein Port offen ist, schickt ein kurzlebiger Container die Nachricht von innen:

```bash
docker run --rm --network chat-net curlimages/curl -s -X POST http://chat-service:8080/messages \
  -H 'Content-Type: application/json' \
  -d '{"roomId":"3f2b1c4e-0000-0000-0000-000000000001","senderId":"anna","senderName":"Anna Muster","content":"Hallo"}'
```

Und nachsehen, ob sie in der Datenbank angekommen ist (die Werte stehen in deiner `.env`):

```bash
docker compose exec postgres psql -U chat -d chat -c "SELECT sent_at, sender_name, content FROM message ORDER BY sent_at DESC LIMIT 5"
```

Die Tabelle `message` legt `postgres/init.sql` an, und zwar **einmal**, beim ersten Start mit
leerem Datenträger. Wer sie ändert, braucht `docker compose down -v`.

### Prüfen, ob der batch-writer tut, was er soll

```bash
scripts/verify-batch-writer.sh   # Szenarien S2 bis S7 auf einem frischen Stack (dauert einige Minuten)
scripts/check-code-rules.sh      # Szenario S8: Regeln aus CLAUDE.md im Quelltext
```

Das erste Skript löscht den Stack samt Datenträger und baut ihn neu auf. Es lässt ihn am Ende laufen.

### Live-Zustellung: Nachrichten sofort anzeigen (SSE)

Der `chat-service` stellt jede angenommene Nachricht per Server-Sent Events an alle Clients zu, die
gerade im selben Raum verbunden sind. Jede Instanz hat dafür eine **eigene** Queue am Exchange
`chat.delivery`. Der Endpunkt ist nur im Docker-Netz erreichbar. Ein Client im Raum öffnen:

```bash
docker run --rm --network chat-net curlimages/curl -sN -H 'Accept: text/event-stream' \
  "http://chat-service:8080/stream?roomId=3f2b1c4e-0000-0000-0000-000000000001"
```

In einem zweiten Terminal die Nachricht von oben senden: sie erscheint sofort im ersten. Mit zwei
Instanzen (`docker compose up -d --scale chat-service=2`) erreicht eine Nachricht beide Clients, egal
an welcher Instanz sie angenommen wurde. Verbindet ein Client nach einem Abbruch mit dem Header
`Last-Event-ID`, holt er nach, was er verpasst hat. Alles Weitere steht in
[`docs/spec-live-zustellung.md`](docs/spec-live-zustellung.md).

```bash
scripts/verify-live-delivery.sh   # Szenarien S2 bis S7 der Live-Zustellung (dauert einige Minuten)
scripts/check-code-rules.sh chat-service   # Szenario S8 für den chat-service
```

**Achtung:** `scripts/verify-live-delivery.sh` löscht zuerst den ganzen Stack samt Datenträger
(`docker compose down -v`). **Alle gespeicherten Nachrichten gehen verloren.** Das gilt wie beim
`batch-writer`-Skript. Beide Skripte teilen ihre Hilfsfunktionen in `scripts/lib-verify.sh`.
`scripts/check-code-rules.sh` ohne Argument prüft den `batch-writer` streng, mit `chat-service`
gilt die Regel aus dem Auftrag, und `--strict` erzwingt die strenge Regel für jeden Ordner.

## Was gebaut wird

| Baustein | Technologie | Aufgabe | Stand |
|---|---|---|---|
| chat-service | Spring Boot 3, Java 21 | Nimmt Nachrichten per `POST /messages` an, legt sie auf Queue und Fanout-Exchange und stellt sie per SSE (`GET /stream`) an die verbundenen Clients zu | vorhanden |
| rabbitmq | RabbitMQ 3.13 | Message Queue zwischen den Services | vorhanden |
| batch-writer | Spring Boot 3, Java 21 | Holt Nachrichten aus `chat.persist` und schreibt sie stapelweise (500 oder 200 ms) in PostgreSQL. Einziger Schreiber | vorhanden |
| postgres | PostgreSQL 16 | Speichert den Chat-Verlauf, Tabelle `message` | vorhanden |
| keycloak | Keycloak | Login (OIDC) | folgt |
| web-gateway | nginx | Einziger nach aussen offener Port | folgt |
| Web-UI | React | Browser-Client | folgt |

Alles unterhalb des Gateways läuft in einem internen Docker-Netzwerk und ist von aussen nicht
erreichbar.

## Dokumente

- [`PLANUNG.md`](PLANUNG.md) — Auftrag, Stack, Architektur, Nachrichtenfluss, Queues, Datenmodell,
  Umsetzungsreihenfolge. Das ist die Grundlage für alles Weitere.
- [`docs/design/2026-08-28-chat-app-planung.html`](docs/design/2026-08-28-chat-app-planung.html)
  — grafische Fassung der Planung, lokal im Browser öffnen.
- [`docs/plan-chat-service.md`](docs/plan-chat-service.md) — Schritt-für-Schritt-Plan, nach dem
  der `chat-service` gebaut wurde. Jeder Schritt mit Test.
- [`docs/spec-batch-writer.md`](docs/spec-batch-writer.md) — Spezifikation des `batch-writer`:
  Vertrag, Verhalten in jedem Fehlerfall, Datenmodell, Entscheidungen, Abnahmekriterien und die
  gemessenen Randfälle.
- [`docs/plan-batch-writer.md`](docs/plan-batch-writer.md) — Umsetzungsplan des `batch-writer`, in
  der Reihenfolge, in der er gebaut wurde (siehe `git log`).
- [`docs/spec-live-zustellung.md`](docs/spec-live-zustellung.md) — Spezifikation der Live-Zustellung:
  Vertrag, Queues, Verhalten in jedem Fall, Entscheidungen, Abnahmekriterien.
- [`docs/plan-live-zustellung.md`](docs/plan-live-zustellung.md) — Umsetzungsplan der Live-Zustellung,
  Schritt für Schritt, mit den Aufträgen an die KI und dem, was ich daran geprüft und geändert habe.
- [`CLAUDE.md`](CLAUDE.md) — Codestil-Regeln für dieses Projekt. Gelten auch für dich.
- [`docs/flipchart-chat-app.png`](docs/flipchart-chat-app.png) — das Flipchart aus der Lektion,
  von dem die Planung ausgeht.

## Codestil, kurz

Der Massstab ist: **kann eine lernende Person jede Zeile vorlesen und sagen, was sie tut?**

- Eine Anweisung pro Zeile, Zwischenresultate in benannte Variablen.
- `for`-Schleife statt Stream, `if` statt verschachteltem Ternary.
- Sprechende Namen in ganzen Wörtern.
- Über jeder Methode ein bis zwei Sätze: was sie tut und warum es sie gibt.
- Kommentare auf Deutsch, als Erklärung an eine Mitlernende.

Die vollständigen Regeln stehen in [`CLAUDE.md`](CLAUDE.md).
