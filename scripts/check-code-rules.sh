#!/usr/bin/env bash
#
# Prüft Szenario S8: hält der Quelltext die Regeln aus CLAUDE.md?
#
#   1. Keine Streams und keine Lambdas.
#   2. Über jeder Klasse und jeder Methode steht ein Kommentar.
#   3. Keine .env im Repository.
#
# Aufruf:   scripts/check-code-rules.sh             prüft batch-writer/
#           scripts/check-code-rules.sh <ordner>    prüft einen anderen Ordner
#
# Das Skript ist eine Näherung mit regulären Ausdrücken, kein Java-Parser.
# Darum ist es bewusst streng: im Zweifel meldet es eine Stelle zu viel.
#
# Bekannte Grenze: ein Konstruktor ganz OHNE Modifizierer ("Name() {") sieht
# aus wie ein Methodenaufruf und wird nicht erkannt. Im batch-writer gibt es
# keinen, jeder Konstruktor dort ist public oder private.

set -u
cd "$(dirname "$0")/.."

DIRECTORY="${1:-batch-writer}"
FAILURES=0

# Meldet ein bestandenes Kriterium.
pass() {
    echo "PASS  $1"
}

# Meldet ein verletztes Kriterium und merkt es sich für den Exit-Code.
fail() {
    echo "FAIL  $1"
    FAILURES=$((FAILURES + 1))
}

# ------------------------------------------------------- 1. Streams und Lambdas

# Das Wort "stream" kommt nirgends im Ordner vor, in keiner Schreibweise und in keiner Dateiart.
# Das ist strenger als nötig, aber so lässt sich nichts übersehen.
stream_hits=$(grep -rniI "stream" --exclude-dir=target "$DIRECTORY" 2>/dev/null)
if [ -z "$stream_hits" ]; then
    pass "Kein Wort 'stream' in $DIRECTORY"
else
    fail "Das Wort 'stream' kommt vor:"
    echo "$stream_hits" | sed 's/^/        /'
fi

# Pfeil und Methodenreferenz sind die Zeichen von Lambdas.
lambda_hits=$(grep -rnE -e '->' -e '::' --include='*.java' --exclude-dir=target "$DIRECTORY" 2>/dev/null)
if [ -z "$lambda_hits" ]; then
    pass "Keine Lambdas und keine Methodenreferenzen in $DIRECTORY"
else
    fail "Lambda oder Methodenreferenz gefunden:"
    echo "$lambda_hits" | sed 's/^/        /'
fi

# ------------------------------------------------------- 2. Kommentare

# Sucht in einer Java-Datei jede Klasse, jedes Record und jede Methode, vor der kein
# Kommentar steht. Annotationen (@Test, @Override ...) zwischen Kommentar und
# Deklaration sind erlaubt. Gibt pro Fund eine Zeile "Datei:Zeile: Text" aus.
find_uncommented_declarations() {
    awk -v file="$1" '
        { lines[NR] = $0 }
        END {
            in_text_block = 0
            for (n = 1; n <= NR; n++) {
                line = lines[n]

                # Textblöcke (""" ... """) enthalten Daten, keine Deklarationen.
                quotes = gsub(/"""/, "&", line)
                if (in_text_block) {
                    if (quotes > 0) { in_text_block = 0 }
                    continue
                }
                if (quotes % 2 == 1) { in_text_block = 1; continue }

                if (!is_declaration(line)) { continue }

                # Von der Deklaration nach oben gehen, Annotationen überspringen.
                k = n - 1
                while (k >= 1 && lines[k] ~ /^[[:space:]]*@/) { k-- }
                previous = (k >= 1) ? lines[k] : ""
                has_comment = (previous ~ /\*\/[[:space:]]*$/) || (previous ~ /^[[:space:]]*\/\//)
                if (!has_comment) {
                    printf "%s:%d: %s\n", file, n, trim(lines[n])
                }
            }
        }

        function trim(text) { gsub(/^[[:space:]]+/, "", text); return text }

        # Ist die Zeile der Anfang einer Klasse, eines Records, einer Schnittstelle,
        # einer Aufzählung, einer Methode oder eines Konstruktors?
        function is_declaration(text) {
            modifiers = "((public|protected|private|static|final|abstract|synchronized)[[:space:]]+)"
            identifier = "[A-Za-z_][A-Za-z0-9_]*"
            type = "[A-Za-z_][A-Za-z0-9_<>,.?\\[\\]]*"

            # Klasse, Record, Schnittstelle, Aufzählung
            if (text ~ ("^[[:space:]]*" modifiers "*(class|record|interface|enum)[[:space:]]+" identifier)) { return 1 }

            # Mit mindestens einem Modifizierer: Methode oder Konstruktor
            if (text ~ ("^[[:space:]]*" modifiers "+(" type "[[:space:]]+)?" identifier "\\(")) { return 1 }

            # Ohne Modifizierer: Rückgabetyp und Name, etwa "void test() {"
            if (text ~ ("^[[:space:]]*" type "[[:space:]]+" identifier "\\(")) {
                split(text, parts, /[[:space:]]+/)
                first = parts[1] == "" ? parts[2] : parts[1]
                if (first ~ /^(return|throw|new|else|assert|case|yield|package|import)$/) { return 0 }
                return 1
            }
            return 0
        }
    ' "$1"
}

uncommented=""
java_files=$(find "$DIRECTORY" -name '*.java' -not -path '*/target/*' | sort)
for java_file in $java_files; do
    found=$(find_uncommented_declarations "$java_file")
    if [ -n "$found" ]; then
        uncommented="$uncommented$found"$'\n'
    fi
done

if [ -z "$uncommented" ]; then
    count=$(echo "$java_files" | wc -l | tr -d ' ')
    pass "Über jeder Klasse und jeder Methode steht ein Kommentar ($count Dateien geprüft)"
else
    fail "Klasse oder Methode ohne Kommentar:"
    echo "$uncommented" | sed '/^$/d' | sed 's/^/        /'
fi

# ------------------------------------------------------- 3. Keine .env im Repository

tracked_env=$(git ls-files | grep -E '(^|/)\.env$')
env_inside=$(find "$DIRECTORY" -name '.env' -not -path '*/target/*' 2>/dev/null)
if [ -z "$tracked_env" ] && [ -z "$env_inside" ]; then
    pass "Keine .env im Repository"
else
    fail "Eine .env ist im Repository oder in $DIRECTORY: $tracked_env $env_inside"
fi

# ------------------------------------------------------- Ergebnis

echo
if [ "$FAILURES" -eq 0 ]; then
    echo "S8: alle Regeln eingehalten."
    exit 0
fi
echo "S8: $FAILURES Regel(n) verletzt."
exit 1
