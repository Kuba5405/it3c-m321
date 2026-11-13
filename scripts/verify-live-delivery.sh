#!/usr/bin/env bash
#
# Prüft die Szenarien S2 bis S7 der Live-Zustellung (docs/spec-live-zustellung.md,
# Abschnitt 5) gegen einen echten docker-compose-Stack.
#
# Aufruf:   scripts/verify-live-delivery.sh            S2 bis S7
#           scripts/verify-live-delivery.sh --with-s1  zusätzlich vorher "mvn clean test" (S1)
#
# Die Szenarien laufen in dieser Reihenfolge auf DEMSELBEN Stack, ohne Aufräumen
# dazwischen. Nur ganz am Anfang wird der Stack samt Datenträger gelöscht (S2).
#
# Die SSE-Clients sind kleine curl-Container im Netz chat-net. Port 8080 ist nicht
# veröffentlicht, also gibt es nur diesen Weg zum Endpunkt GET /stream. Jede Instanz
# wird einzeln über ihre IP angesprochen, nicht über den Docker-DNS-Namen (der würde
# irgendeine der beiden wählen).
#
# Voraussetzung: Docker läuft, und im Wurzelverzeichnis liegt eine .env
# (cp .env.example .env). Der Stack bleibt am Ende stehen.

set -u
cd "$(dirname "$0")/.."

# Gemeinsame Hilfsfunktionen: pass, fail, report, wait_until, db, queue_messages, .env laden.
. scripts/lib-verify.sh

CLIENT_PREFIX="live-verify"

# ---------------------------------------------------------------- Hilfsfunktionen

# Gibt es die Queue überhaupt? (0 = ja)
queue_exists() {
    docker compose exec -T rabbitmq rabbitmqctl -q list_queues name 2>/dev/null | grep -qx "$1"
}

# Gibt alle Instanzqueues aus: name consumers messages messages_ready messages_unacknowledged.
live_queues() {
    docker compose exec -T rabbitmq rabbitmqctl -q list_queues name consumers messages messages_ready messages_unacknowledged 2>/dev/null \
        | awk '$1 ~ /^chat\.live\./'
}

# Zahl der Instanzqueues.
live_queue_count() {
    live_queues | wc -l | tr -d ' '
}

# Zahl der Instanzqueues mit genau einem Consumer.
live_queues_with_one_consumer() {
    live_queues | awk '$2 == 1' | wc -l | tr -d ' '
}

# Zahl der Bindungen einer Instanzqueue an chat.delivery.
live_bindings() {
    docker compose exec -T rabbitmq rabbitmqctl -q list_bindings source_name destination_name destination_kind 2>/dev/null \
        | awk '$1 == "chat.delivery" && $2 ~ /^chat\.live\./ && $3 == "queue"' | wc -l | tr -d ' '
}

# Bedingung für wait_until: es gibt genau N Instanzqueues, jede mit einem Consumer.
has_live_queues() {
    [ "$(live_queue_count)" -eq "$1" ] && [ "$(live_queues_with_one_consumer)" -eq "$1" ]
}

# Gibt für jede Instanz des chat-service die IP im Netz chat-net aus, eine pro Zeile.
instance_ips() {
    local container
    for container in $(docker compose ps -q chat-service); do
        docker inspect -f '{{(index .NetworkSettings.Networks "chat-net").IPAddress}}' "$container"
    done
}

# Startet einen SSE-Client im Hintergrund. Seine Ausgabe liest man mit "docker logs".
# Aufruf: start_client name ip raum [last-event-id]
start_client() {
    local name="$CLIENT_PREFIX-$1"
    local url="http://$2:8080/stream?roomId=$3"
    docker rm -f "$name" > /dev/null 2>&1
    if [ -n "${4:-}" ]; then
        docker run -d --name "$name" --network chat-net curlimages/curl -sN \
            -H 'Accept: text/event-stream' -H "Last-Event-ID: $4" "$url" > /dev/null
    else
        docker run -d --name "$name" --network chat-net curlimages/curl -sN \
            -H 'Accept: text/event-stream' "$url" > /dev/null
    fi
}

# Zahl der Ereignisse (id:-Zeilen), die ein Client bisher bekommen hat.
client_events() {
    docker logs "$CLIENT_PREFIX-$1" 2>&1 | grep -c '^id:'
}

# Zahl der VERSCHIEDENEN Nachrichten-IDs eines Clients.
client_distinct_events() {
    docker logs "$CLIENT_PREFIX-$1" 2>&1 | grep '^id:' | sort -u | wc -l | tr -d ' '
}

# Bedingung für wait_until: ein Client hat mindestens N Ereignisse.
client_has() {
    [ "$(client_events "$1")" -ge "$2" ]
}

# Gibt die Nummern aus, die ein Absender (Label) in den Nachrichten eines Clients hat,
# in der Reihenfolge des Eintreffens. Der Inhalt lautet "<label> <nummer>".
client_numbers() {
    docker logs "$CLIENT_PREFIX-$1" 2>&1 | grep '^data:' | grep -o "\"content\":\"$2 [0-9]*\"" | grep -o '[0-9]*"$' | tr -d '"'
}

# Stimmt die Reihenfolge? Der Absender hat genau die Nummern 1 bis N, ohne Lücke und Vertauschung.
numbers_are_one_to_n() {
    local actual
    actual=$(client_numbers "$1" "$2" | tr '\n' ' ')
    local expected
    expected=$(seq 1 "$3" | tr '\n' ' ')
    [ "$actual" = "$expected" ]
}

# Räumt alle Test-Clients weg.
remove_clients() {
    local names
    names=$(docker ps -aq --filter "name=$CLIENT_PREFIX-")
    if [ -n "$names" ]; then
        docker rm -f $names > /dev/null 2>&1
    fi
}

# Schickt N Nachrichten per POST /messages an EINE Instanz, nacheinander. Der Inhalt lautet
# "<label> <nummer>". Gibt aus, wie viele mit 202 beantwortet wurden.
# Aufruf: send_to ip raum absender label anzahl
send_to() {
    local ip=$1 room=$2 sender=$3 label=$4 count=$5
    docker run --rm -i --network chat-net --entrypoint sh curlimages/curl -s <<SCRIPT | grep -c '^202$'
i=1
while [ \$i -le $count ]; do
    curl -s -o /dev/null -w '%{http_code}\n' -X POST http://$ip:8080/messages \
        -H 'Content-Type: application/json' \
        -d "{\"roomId\":\"$room\",\"senderId\":\"$sender\",\"senderName\":\"Anna Muster\",\"content\":\"$label \$i\"}"
    i=\$((i + 1))
done
SCRIPT
}

# HTTP-Statuscode einer GET-Anfrage an den Stream. Bricht nach 5 Sekunden ab.
# Aufruf: stream_status ip "roomId=..." [last-event-id]
stream_status() {
    if [ -n "${3:-}" ]; then
        docker run --rm --network chat-net curlimages/curl -s -o /dev/null -w '%{http_code}' --max-time 5 \
            -H 'Accept: text/event-stream' -H "Last-Event-ID: $3" "http://$1:8080/stream?$2"
    else
        docker run --rm --network chat-net curlimages/curl -s -o /dev/null -w '%{http_code}' --max-time 5 \
            -H 'Accept: text/event-stream' "http://$1:8080/stream?$2"
    fi
}

# Bedingung für wait_until: der Postgres-Container ist gesund.
database_is_healthy() {
    [ "$(docker compose ps postgres --format '{{.Health}}')" = "healthy" ]
}

# Bedingung für wait_until: in der Datenbank stehen N Zeilen eines Raums.
room_has_rows() {
    [ "$(db "SELECT count(*) FROM message WHERE room_id = '$1'")" = "$2" ]
}

# Eine neue, zufällige Raum-ID, damit sich Szenarien nicht gegenseitig stören.
new_room() {
    uuidgen | tr 'A-Z' 'a-z'
}

remove_clients

# ---------------------------------------------------------------- S1 (nur auf Wunsch)

if [ "${1:-}" = "--with-s1" ]; then
    echo "--- S1: mvn clean test (dauert einige Minuten)"
    if mvn -q clean test > /tmp/verify-live-s1.log 2>&1; then
        report S1 "mvn clean test: alles grün" 0
    else
        report S1 "mvn clean test: Fehler, siehe /tmp/verify-live-s1.log" 1
    fi
fi

# ---------------------------------------------------------------- S2

echo "--- S2: frischer Stack, Instanzqueue vor der ersten Nachricht"
docker compose down -v > /dev/null 2>&1
docker compose up -d --build > /tmp/verify-live-up.log 2>&1

running=0
for service in rabbitmq postgres chat-service batch-writer; do
    state=$(docker compose ps "$service" --format '{{.State}}')
    if [ "$state" = "running" ]; then
        running=$((running + 1))
    else
        echo "      $service: $state"
    fi
done
published=$(docker compose ps --format '{{.Ports}}' | grep -c -e '->')
published_in_config=$(docker compose config | grep -c published)
declared=$(grep -c "ports" docker-compose.yml)
# Noch KEINE Nachricht gesendet: die Instanzqueue muss trotzdem schon da sein.
if wait_until 60 has_live_queues 1; then
    queue_ok=0
else
    queue_ok=1
fi
bindings=$(live_bindings)
if [ "$running" -eq 4 ] && [ "$published" -eq 0 ] && [ "$published_in_config" -eq 0 ] && [ "$declared" -eq 0 ] \
    && [ "$queue_ok" -eq 0 ] && [ "$bindings" -eq 1 ]; then
    report S2 "4 Dienste laufen, kein Port veröffentlicht, 1 Instanzqueue mit 1 Consumer und 1 Bindung vor der ersten Nachricht" 0
else
    report S2 "laufend=$running (erwartet 4), veröffentlicht=$published/$published_in_config, ports-Zeilen=$declared, Instanzqueues mit 1 Consumer=$(live_queues_with_one_consumer) (erwartet 1), Bindungen=$bindings" 1
fi

# ---------------------------------------------------------------- S3

echo "--- S3: zwei Instanzen, Raum A (zwei Clients) und Raum B (ein Client)"
docker compose up -d --scale chat-service=2 > /dev/null 2>&1
wait_until 90 has_live_queues 2
ips=($(instance_ips))
ip_one=${ips[0]:-}
ip_two=${ips[1]:-}
room_a=$(new_room)
room_b=$(new_room)
start_client a1 "$ip_one" "$room_a"
start_client a2 "$ip_two" "$room_a"
start_client b "$ip_two" "$room_b"
sleep 3
sent_one=$(send_to "$ip_one" "$room_a" sender-one S3-one 200)
sent_two=$(send_to "$ip_two" "$room_a" sender-two S3-two 200)
send_to "$ip_one" "$room_b" sender-b S3-b 1 > /dev/null
wait_until 60 client_has a1 400
wait_until 60 client_has a2 400
wait_until 30 client_has b 1
sleep 2
a1_total=$(client_events a1)
a1_distinct=$(client_distinct_events a1)
a2_total=$(client_events a2)
a2_distinct=$(client_distinct_events a2)
b_total=$(client_events b)
order_ok=1
if numbers_are_one_to_n a1 S3-one 200 && numbers_are_one_to_n a1 S3-two 200 \
    && numbers_are_one_to_n a2 S3-one 200 && numbers_are_one_to_n a2 S3-two 200; then
    order_ok=0
fi
if [ "$a1_total" -eq 400 ] && [ "$a1_distinct" -eq 400 ] && [ "$a2_total" -eq 400 ] && [ "$a2_distinct" -eq 400 ] \
    && [ "$order_ok" -eq 0 ] && [ "$b_total" -eq 1 ]; then
    report S3 "gesendet=$sent_one+$sent_two, A1=$a1_total (verschieden $a1_distinct), A2=$a2_total (verschieden $a2_distinct), Reihenfolge je Absender stimmt, B=$b_total (nur die eigene Kontrollnachricht)" 0
else
    report S3 "A1=$a1_total/$a1_distinct, A2=$a2_total/$a2_distinct (erwartet je 400/400), Reihenfolge ok=$([ "$order_ok" -eq 0 ] && echo ja || echo NEIN), B=$b_total (erwartet 1)" 1
fi
remove_clients

# ---------------------------------------------------------------- S4

echo "--- S4: zwei Instanzen, dann zurück auf eine"
queues_before=$(live_queue_count)
consumers_before=$(live_queues_with_one_consumer)
bindings_before=$(live_bindings)
docker compose up -d --scale chat-service=1 > /dev/null 2>&1
scale_started=$(date +%s)
wait_until 40 has_live_queues 1
seconds_needed=$(( $(date +%s) - scale_started ))
queues_after=$(live_queue_count)
persist_ok=1
if queue_exists chat.persist && queue_exists chat.dlq; then
    persist_ok=0
fi
if [ "$queues_before" -eq 2 ] && [ "$consumers_before" -eq 2 ] && [ "$bindings_before" -eq 2 ] \
    && [ "$queues_after" -eq 1 ] && [ "$seconds_needed" -le 30 ] && [ "$persist_ok" -eq 0 ]; then
    report S4 "vorher 2 Queues mit je 1 Consumer und 2 Bindungen, nach ${seconds_needed} s nur noch $queues_after, chat.persist und chat.dlq bleiben" 0
else
    report S4 "vorher Queues=$queues_before, Consumer=$consumers_before, Bindungen=$bindings_before (erwartet 2/2/2), nachher Queues=$queues_after nach ${seconds_needed} s (erwartet 1 in höchstens 30 s), persist/dlq vorhanden=$([ "$persist_ok" -eq 0 ] && echo ja || echo NEIN)" 1
fi

# ---------------------------------------------------------------- S5

echo "--- S5: batch-writer und postgres gestoppt, 100 Nachrichten"
docker compose stop batch-writer postgres > /dev/null 2>&1
ips=($(instance_ips))
ip_one=${ips[0]:-}
room_c=$(new_room)
start_client c "$ip_one" "$room_c"
sleep 3
persist_before=$(queue_messages chat.persist)
send_started=$(date +%s)
sent=$(send_to "$ip_one" "$room_c" anna S5 100)
wait_until 5 client_has c 100
seconds_needed=$(( $(date +%s) - send_started ))
received=$(client_events c)
persist_growth=$(( $(queue_messages chat.persist) - persist_before ))
remove_clients
docker compose start postgres batch-writer > /dev/null 2>&1
wait_until 90 database_is_healthy
if wait_until 90 room_has_rows "$room_c" 100; then
    rows=100
else
    rows=$(db "SELECT count(*) FROM message WHERE room_id = '$room_c'")
fi
if [ "$received" -eq 100 ] && [ "$seconds_needed" -le 5 ] && [ "$rows" = "100" ]; then
    report S5 "gesendet=$sent, beim Client $received nach ${seconds_needed} s, chat.persist +$persist_growth, nach dem Neustart $rows Zeilen" 0
else
    report S5 "beim Client $received (erwartet 100) nach ${seconds_needed} s (erwartet höchstens 5), Zeilen nach dem Neustart=$rows (erwartet 100)" 1
fi

# ---------------------------------------------------------------- S6

echo "--- S6: eine von zwei Instanzen angehalten, 5000 Nachrichten"
docker compose up -d --scale chat-service=2 > /dev/null 2>&1
wait_until 90 has_live_queues 2
containers=($(docker compose ps -q chat-service))
ips=($(instance_ips))
healthy_ip=${ips[0]:-}
paused_ip=${ips[1]:-}
paused_container=${containers[1]:-}
room_d=$(new_room)
start_client d "$healthy_ip" "$room_d"
sleep 3
docker pause "$paused_container" > /dev/null 2>&1
# 10 Sender mit je 500 Nachrichten gleichzeitig, damit es schnell geht.
for worker in 1 2 3 4 5 6 7 8 9 10; do
    send_to "$healthy_ip" "$room_d" "sender-$worker" "S6-$worker" 500 > /dev/null &
done
# Eingefroren heisst: keine Heartbeats. Nach etwa 10 s löscht der Broker die Queue.
# Darum wird in den ersten Sekunden mehrfach gemessen und der Höchstwert gemerkt.
max_messages=0
max_ready=0
for sample in 1 2 3 4 5 6 7 8; do
    while read -r name consumers messages ready unacknowledged; do
        if [ "$messages" -gt "$max_messages" ]; then
            max_messages=$messages
        fi
        if [ "$ready" -gt "$max_ready" ]; then
            max_ready=$ready
        fi
    done < <(live_queues)
    sleep 1
done
wait
wait_until 90 client_has d 5000
healthy_total=$(client_distinct_events d)
docker unpause "$paused_container" > /dev/null 2>&1
resumed=1
if wait_until 90 has_live_queues 2; then
    resumed=0
fi
room_e=$(new_room)
start_client e "$paused_ip" "$room_e"
sleep 3
send_to "$healthy_ip" "$room_e" anna S6-control 1 > /dev/null
control=1
if wait_until 30 client_has e 1; then
    control=0
fi
remove_clients
# max_ready muss fast 750 erreichen, sonst hat der Test das Limit nie berührt.
if [ "$max_messages" -le 1000 ] && [ "$max_ready" -le 750 ] && [ "$max_ready" -ge 100 ] \
    && [ "$healthy_total" -eq 5000 ] && [ "$resumed" -eq 0 ] && [ "$control" -eq 0 ]; then
    report S6 "höchste Queue-Tiefe=$max_messages (erlaubt 1000), davon bereit=$max_ready (erlaubt 750), gesunder Client=$healthy_total, nach dem Fortsetzen 2 Queues und die Kontrollnachricht kam an" 0
else
    report S6 "Tiefe=$max_messages (erlaubt 1000), bereit=$max_ready (erlaubt 750, mindestens 100 nötig, sonst wurde das Limit nie erreicht), gesunder Client=$healthy_total (erwartet 5000), wieder 2 Queues=$([ "$resumed" -eq 0 ] && echo ja || echo NEIN), Kontrollnachricht=$([ "$control" -eq 0 ] && echo ja || echo NEIN)" 1
fi

# ---------------------------------------------------------------- S7

echo "--- S7: Client trennt nach einer Nachricht, 50 Nachrichten, Neuverbindung mit Last-Event-ID"
ips=($(instance_ips))
ip_one=${ips[0]:-}
room_f=$(new_room)
start_client f1 "$ip_one" "$room_f"
sleep 3
send_to "$ip_one" "$room_f" anna S7-first 1 > /dev/null
wait_until 30 client_has f1 1
cursor=$(docker logs "$CLIENT_PREFIX-f1" 2>&1 | grep '^id:' | head -1 | sed 's/^id: *//' | tr -d '[:space:]')
docker rm -f "$CLIENT_PREFIX-f1" > /dev/null 2>&1
send_to "$ip_one" "$room_f" anna S7 50 > /dev/null
sleep 10
start_client f2 "$ip_one" "$room_f" "$cursor"
wait_until 30 client_has f2 50
after_replay=$(client_events f2)
send_to "$ip_one" "$room_f" anna S7-live 1 > /dev/null
live=1
if wait_until 30 client_has f2 51; then
    live=0
fi
total=$(client_events f2)
distinct=$(client_distinct_events f2)
cursor_repeated=$(docker logs "$CLIENT_PREFIX-f2" 2>&1 | grep -c "^id: *$cursor")
order_ok=1
if numbers_are_one_to_n f2 S7 50; then
    order_ok=0
fi
status_unknown=$(stream_status "$ip_one" "roomId=$room_f" "$(uuidgen | tr 'A-Z' 'a-z')")
status_no_room=$(stream_status "$ip_one" "")
status_bad_room=$(stream_status "$ip_one" "roomId=keine-uuid")
status_bad_cursor=$(stream_status "$ip_one" "roomId=$room_f" "keine-uuid")
remove_clients
if [ "$after_replay" -eq 50 ] && [ "$order_ok" -eq 0 ] && [ "$cursor_repeated" -eq 0 ] && [ "$live" -eq 0 ] \
    && [ "$total" -eq 51 ] && [ "$distinct" -eq 51 ] \
    && [ "$status_unknown" = "409" ] && [ "$status_no_room" = "400" ] && [ "$status_bad_room" = "400" ] && [ "$status_bad_cursor" = "400" ]; then
    report S7 "nach der Neuverbindung genau $after_replay in Reihenfolge, Cursor nicht wiederholt, danach live (gesamt $total, verschieden $distinct); unbekannte ID=$status_unknown, roomId fehlt=$status_no_room, keine UUID=$status_bad_room, Last-Event-ID keine UUID=$status_bad_cursor" 0
else
    report S7 "nach der Neuverbindung $after_replay (erwartet 50), Reihenfolge ok=$([ "$order_ok" -eq 0 ] && echo ja || echo NEIN), Cursor wiederholt=$cursor_repeated, live=$([ "$live" -eq 0 ] && echo ja || echo NEIN), gesamt=$total/$distinct (erwartet 51/51), Status unbekannt=$status_unknown (409), ohne roomId=$status_no_room (400), roomId keine UUID=$status_bad_room (400), Last-Event-ID keine UUID=$status_bad_cursor (400)" 1
fi

# ---------------------------------------------------------------- Ergebnis

echo
if [ "$FAILURES" -eq 0 ]; then
    echo "Alle geprüften Szenarien bestanden. Der Stack läuft weiter (Aufräumen: docker compose down -v)."
    exit 0
fi
echo "$FAILURES Szenario(en) nicht bestanden."
exit 1
