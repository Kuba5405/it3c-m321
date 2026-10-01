#!/usr/bin/env bash
#
# Prüft die Szenarien S2 bis S7 der Spezifikation (docs/spec-batch-writer.md,
# Abschnitt 6) gegen einen echten docker-compose-Stack.
#
# Aufruf:   scripts/verify-batch-writer.sh            S2 bis S7
#           scripts/verify-batch-writer.sh --with-s1  zusätzlich vorher "mvn clean test" (S1)
#
# Die Szenarien laufen in dieser Reihenfolge auf DEMSELBEN Stack, ohne
# Aufräumen dazwischen. Nur ganz am Anfang wird der Stack samt Datenträger
# gelöscht, damit es ein "frischer Klon" ist (S2).
#
# Voraussetzung: Docker läuft, und im Wurzelverzeichnis liegt eine .env
# (cp .env.example .env). Der Stack bleibt am Ende stehen.

set -u
cd "$(dirname "$0")/.."

if [ ! -f .env ]; then
    echo "Keine .env gefunden. Zuerst: cp .env.example .env"
    exit 2
fi
set -a
. ./.env
set +a

FAILURES=0
ROOM_ID="3f2b1c4e-0000-0000-0000-000000000001"

# ---------------------------------------------------------------- Hilfsfunktionen

# Meldet ein bestandenes Szenario.
pass() {
    echo "PASS  $1  $2"
}

# Meldet ein nicht bestandenes Szenario und merkt es sich für den Exit-Code.
fail() {
    echo "FAIL  $1  $2"
    FAILURES=$((FAILURES + 1))
}

# Meldet je nach Ergebnis des letzten Arguments PASS oder FAIL.
# Aufruf: report S3 "Beschreibung" 0   (0 = bestanden)
report() {
    if [ "$3" -eq 0 ]; then
        pass "$1" "$2"
    else
        fail "$1" "$2"
    fi
}

# Führt eine SQL-Abfrage in der Datenbank aus und gibt das Ergebnis ohne Leerraum zurück.
db() {
    docker compose exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc "$1" 2>/dev/null | tr -d '[:space:]'
}

# Zahl der Zeilen in der Tabelle, 0 wenn die Datenbank nicht antwortet.
row_count() {
    local value
    value=$(db "SELECT count(*) FROM message")
    echo "${value:-0}"
}

# Zahl der Nachrichten in einer Queue (bereite und unbestätigte), 0 wenn es sie nicht gibt.
queue_messages() {
    local value
    value=$(docker compose exec -T rabbitmq rabbitmqctl list_queues name messages 2>/dev/null | awk -v queue="$1" '$1 == queue {print $2}')
    echo "${value:-0}"
}

# Zahl der Verbraucher an einer Queue, 0 wenn es sie nicht gibt.
queue_consumers() {
    local value
    value=$(docker compose exec -T rabbitmq rabbitmqctl list_queues name consumers 2>/dev/null | awk -v queue="$1" '$1 == queue {print $2}')
    echo "${value:-0}"
}

# Zahl der abgeschlossenen Transaktionen der Datenbank seit ihrem Start.
# Die Statistik wird mit Verzögerung nachgeführt, darum vorher kurz warten.
transaction_count() {
    sleep 2
    db "SELECT xact_commit FROM pg_stat_database WHERE datname = current_database()"
}

# Wartet höchstens N Sekunden, bis ein Befehl gelingt. Gibt 0 zurück, wenn er gelang.
# Aufruf: wait_until 60 befehl argument ...
wait_until() {
    local limit=$1
    shift
    local start
    start=$(date +%s)
    while true; do
        if "$@"; then
            return 0
        fi
        local now
        now=$(date +%s)
        if [ $((now - start)) -ge "$limit" ]; then
            return 1
        fi
        sleep 1
    done
}

# Bedingung für wait_until: die Tabelle hat mindestens N Zeilen UND chat.persist ist leer.
all_rows_stored() {
    local rows
    rows=$(row_count)
    local waiting
    waiting=$(queue_messages chat.persist)
    [ "$rows" -ge "$1" ] && [ "$waiting" -eq 0 ]
}

# Bedingung für wait_until: eine Queue hat genau N Nachrichten.
queue_has() {
    [ "$(queue_messages "$1")" -eq "$2" ]
}

# Bedingung für wait_until: der Postgres-Container ist gesund.
database_is_healthy() {
    [ "$(docker compose ps postgres --format '{{.Health}}')" = "healthy" ]
}

# Schickt N Nachrichten per POST /messages aus dem Docker-Netz an den chat-service,
# nacheinander. Gibt aus, wie viele mit 202 beantwortet wurden.
send_messages() {
    local count=$1
    local label=$2
    docker run --rm -i --network chat-net --entrypoint sh curlimages/curl -s <<SCRIPT | grep -c '^202$'
i=1
while [ \$i -le $count ]; do
    curl -s -o /dev/null -w '%{http_code}\n' -X POST http://chat-service:8080/messages \
        -H 'Content-Type: application/json' \
        -d "{\"roomId\":\"$ROOM_ID\",\"senderId\":\"anna\",\"senderName\":\"Anna Muster\",\"content\":\"$label \$i\"}"
    i=\$((i + 1))
done
SCRIPT
}

# Legt EINE Nachricht direkt in chat.persist, über die Management-Schnittstelle von
# RabbitMQ und mit NUR dem Header content_type (so prüft Szenario S5).
publish_with_only_content_type() {
    local message_id=$1
    local payload="{\\\"id\\\":\\\"$message_id\\\",\\\"roomId\\\":\\\"$ROOM_ID\\\",\\\"senderId\\\":\\\"anna\\\",\\\"senderName\\\":\\\"Anna Muster\\\",\\\"content\\\":\\\"Duplikat\\\",\\\"sentAt\\\":\\\"2026-10-01T07:38:47.713518425Z\\\"}"
    local body="{\"properties\":{\"content_type\":\"application/json\"},\"routing_key\":\"chat.persist\",\"payload\":\"$payload\",\"payload_encoding\":\"string\"}"
    docker run --rm --network chat-net curlimages/curl -s -u "$RABBITMQ_USER:$RABBITMQ_PASSWORD" \
        -X POST http://rabbitmq:15672/api/exchanges/%2F/amq.default/publish \
        -H 'content-type: application/json' -d "$body" > /dev/null
}

# Gibt für jede Instanz des batch-writer Name und Startzeit aus. Ändert sich das,
# wurde ein Container neu gestartet.
writer_start_times() {
    local container
    for container in $(docker compose ps -q batch-writer); do
        docker inspect -f '{{.Name}} {{.State.StartedAt}}' "$container"
    done
}

# ---------------------------------------------------------------- S1 (nur auf Wunsch)

if [ "${1:-}" = "--with-s1" ]; then
    echo "--- S1: mvn clean test (dauert einige Minuten)"
    if mvn -q clean test > /tmp/verify-s1.log 2>&1; then
        report S1 "mvn clean test: alles grün" 0
    else
        report S1 "mvn clean test: Fehler, siehe /tmp/verify-s1.log" 1
    fi
fi

# ---------------------------------------------------------------- S2

echo "--- S2: frischer Stack"
docker compose down -v > /dev/null 2>&1
docker compose up -d --build > /tmp/verify-up.log 2>&1
sleep 10

running=0
for service in rabbitmq postgres chat-service batch-writer; do
    state=$(docker compose ps "$service" --format '{{.State}}')
    if [ "$state" = "running" ]; then
        running=$((running + 1))
    else
        echo "      $service: $state"
    fi
done
published=$(docker compose ps --format '{{.Ports}}' | grep -c -e '->' )
declared=$(grep -c "ports" docker-compose.yml)
if [ "$running" -eq 4 ] && [ "$published" -eq 0 ] && [ "$declared" -eq 0 ]; then
    report S2 "4 Dienste laufen, kein Port veröffentlicht" 0
else
    report S2 "laufend=$running (erwartet 4), veröffentlicht=$published, ports-Zeilen=$declared (erwartet 0)" 1
fi

# ---------------------------------------------------------------- S3

echo "--- S3: 1000 Nachrichten über POST /messages"
rows_before=$(row_count)
accepted=$(send_messages 1000 "S3")
if wait_until 60 all_rows_stored $((rows_before + 1000)); then
    rows_after=$(row_count)
    report S3 "gesendet=$accepted, Zeilen +$((rows_after - rows_before)), chat.persist leer" 0
else
    report S3 "gesendet=$accepted, Zeilen +$(( $(row_count) - rows_before )), chat.persist=$(queue_messages chat.persist)" 1
fi

# ---------------------------------------------------------------- S4

echo "--- S4: Schreiber gestoppt, 1000 senden, Schreiber wieder starten"
docker compose stop batch-writer > /dev/null 2>&1
rows_before=$(row_count)
accepted=$(send_messages 1000 "S4")
wait_until 30 queue_has chat.persist 1000
queued=$(queue_messages chat.persist)
transactions_before=$(transaction_count)
docker compose start batch-writer > /dev/null 2>&1
if wait_until 60 all_rows_stored $((rows_before + 1000)); then
    transactions_after=$(transaction_count)
    added=$(( $(row_count) - rows_before ))
    transactions=$((transactions_after - transactions_before))
    if [ "$added" -eq 1000 ] && [ "$transactions" -le 100 ]; then
        report S4 "in der Queue=$queued, Zeilen +$added, Transaktionen +$transactions (erlaubt: 100)" 0
    else
        report S4 "Zeilen +$added (erwartet 1000), Transaktionen +$transactions (erlaubt: 100)" 1
    fi
else
    report S4 "nach 60 s fehlen Nachrichten: Zeilen +$(( $(row_count) - rows_before )), in der Queue=$queued" 1
fi

# ---------------------------------------------------------------- S5

echo "--- S5: dieselbe Nachricht zweimal direkt in chat.persist"
duplicate_id=$(uuidgen | tr 'A-Z' 'a-z')
publish_with_only_content_type "$duplicate_id"
publish_with_only_content_type "$duplicate_id"
wait_until 30 queue_has chat.persist 0
sleep 3
copies=$(db "SELECT count(*) FROM message WHERE id = '$duplicate_id'")
dead_letters=$(queue_messages chat.dlq)
if [ "$copies" = "1" ] && [ "$dead_letters" -eq 0 ]; then
    report S5 "Zeilen mit dieser id=$copies, chat.dlq=$dead_letters" 0
else
    report S5 "Zeilen mit dieser id=$copies (erwartet 1), chat.dlq=$dead_letters (erwartet 0)" 1
fi

# ---------------------------------------------------------------- S6

echo "--- S6: zwei Instanzen"
since=$(date -u +%Y-%m-%dT%H:%M:%SZ)
docker compose up -d --scale batch-writer=2 > /dev/null 2>&1
sleep 10
consumers=$(queue_consumers chat.persist)
rows_before=$(row_count)
accepted=$(send_messages 1000 "S6")
if wait_until 60 all_rows_stored $((rows_before + 1000)); then
    added=$(( $(row_count) - rows_before ))
    total=$(db "SELECT count(*) FROM message")
    distinct=$(db "SELECT count(DISTINCT id) FROM message")
    working=$(docker compose logs --since "$since" batch-writer 2>/dev/null | grep "Batch done" | awk '{print $1}' | sort -u | wc -l | tr -d ' ')
    if [ "$consumers" -eq 2 ] && [ "$added" -eq 1000 ] && [ "$total" = "$distinct" ] && [ "$working" -eq 2 ]; then
        report S6 "Verbraucher=$consumers, Zeilen +$added, keine doppelten (count=$total, distinct=$distinct), Instanzen mit Arbeit=$working" 0
    else
        report S6 "Verbraucher=$consumers (erwartet 2), Zeilen +$added, count=$total, distinct=$distinct, Instanzen mit Arbeit=$working (erwartet 2)" 1
    fi
else
    report S6 "nach 60 s fehlen Nachrichten, Verbraucher=$consumers" 1
fi

# ---------------------------------------------------------------- S7

echo "--- S7: Postgres steht 15 s still, 300 Nachrichten dazwischen"
starts_before=$(writer_start_times)
rows_before=$(row_count)
outage_started=$(date +%s)
docker compose stop postgres > /dev/null 2>&1
accepted=$(send_messages 300 "S7")
elapsed=$(( $(date +%s) - outage_started ))
if [ "$elapsed" -lt 15 ]; then
    sleep $((15 - elapsed))
fi
recovery_started=$(date +%s)
docker compose start postgres > /dev/null 2>&1
if wait_until 90 database_is_healthy && wait_until 90 all_rows_stored $((rows_before + 300)); then
    recovery=$(( $(date +%s) - recovery_started ))
    added=$(( $(row_count) - rows_before ))
    starts_after=$(writer_start_times)
    if [ "$added" -eq 300 ] && [ "$starts_before" = "$starts_after" ] && [ "$(queue_messages chat.dlq)" -eq 0 ]; then
        report S7 "gesendet=$accepted, Zeilen +$added, ${recovery} s nach dem Neustart vollständig, kein Neustart des batch-writer, chat.dlq leer" 0
    else
        report S7 "Zeilen +$added (erwartet 300), Neustart des batch-writer=$([ "$starts_before" = "$starts_after" ] && echo nein || echo JA), chat.dlq=$(queue_messages chat.dlq)" 1
    fi
else
    report S7 "nach 90 s fehlen Nachrichten: Zeilen +$(( $(row_count) - rows_before )), chat.persist=$(queue_messages chat.persist)" 1
fi

# ---------------------------------------------------------------- Ergebnis

echo
if [ "$FAILURES" -eq 0 ]; then
    echo "Alle geprüften Szenarien bestanden. Der Stack läuft weiter (Aufräumen: docker compose down -v)."
    exit 0
fi
echo "$FAILURES Szenario(en) nicht bestanden."
exit 1
