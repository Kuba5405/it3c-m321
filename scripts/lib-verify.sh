#!/usr/bin/env bash
#
# Gemeinsame Hilfsfunktionen der Abnahmeskripte (verify-batch-writer.sh und
# verify-live-delivery.sh). Wird mit "source" eingelesen, nie einzeln gestartet.
#
# Warum eine eigene Datei: beide Skripte brauchten dieselben Funktionen. Ein
# Fehler in einer Kopie (zum Beispiel ein geändertes Ausgabeformat von
# rabbitmqctl) liesse die andere unbemerkt kaputt.
#
# Voraussetzung: das aufrufende Skript steht im Wurzelverzeichnis des Projekts
# (cd "$(dirname "$0")/.."), dort liegt die .env.

if [ ! -f .env ]; then
    echo "Keine .env gefunden. Zuerst: cp .env.example .env"
    exit 2
fi
set -a
. ./.env
set +a

FAILURES=0

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

# Führt eine SQL-Abfrage in der Datenbank aus und gibt das Ergebnis ohne Leerraum zurück.
db() {
    docker compose exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc "$1" 2>/dev/null | tr -d '[:space:]'
}

# Zahl der Nachrichten in einer festen Queue (bereite und unbestätigte), 0 wenn es sie nicht gibt.
queue_messages() {
    local value
    value=$(docker compose exec -T rabbitmq rabbitmqctl list_queues name messages 2>/dev/null | awk -v queue="$1" '$1 == queue {print $2}')
    echo "${value:-0}"
}
