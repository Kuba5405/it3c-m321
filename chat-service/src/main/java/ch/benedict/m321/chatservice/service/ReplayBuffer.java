package ch.benedict.m321.chatservice.service;

import ch.benedict.m321.chatservice.dto.ChatMessage;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Das Gedächtnis einer Instanz: die letzten 1000 verschiedenen Nachrichten,
 * über alle Räume zusammen, in der Reihenfolge ihres Eintreffens.
 *
 * Warum es sie gibt: Ein Client, dessen Verbindung abbricht, schickt beim
 * Zurückkommen die letzte Nachrichten-ID mit. Aus diesem Puffer holt die
 * Instanz, was ihm fehlt. Die Datenbank wird dafür nicht gefragt, so bleibt
 * die Zustellung unabhängig vom batch-writer (Spezifikation 4.4 und 4.6).
 *
 * Die Klasse ist bewusst NICHT thread-sicher. Den Schutz bei mehreren
 * Threads übernimmt LiveDeliveryState, das sie als einziges benutzt.
 */
public class ReplayBuffer {

    /** So viele Nachrichten merkt sich eine Instanz höchstens. */
    static final int CAPACITY = 1000;

    /**
     * Eine LinkedHashMap merkt sich die Einfügereihenfolge und findet eine ID
     * trotzdem sofort. Ein einfacher Listen-Durchlauf wäre bei jeder
     * Dublettenprüfung langsam.
     */
    private final Map<UUID, ChatMessage> messages = new LinkedHashMap<>();

    /**
     * Nimmt eine Nachricht auf, falls ihre ID noch unbekannt ist.
     *
     * @return true bei einer neuen Nachricht, false bei einer, die schon da war
     */
    public boolean add(ChatMessage message) {
        UUID id = message.id();
        boolean alreadyKnown = messages.containsKey(id);
        if (alreadyKnown) {
            return false;
        }
        messages.put(id, message);
        removeOldestWhenFull();
        return true;
    }

    /** Wirft die älteste Nachricht weg, sobald mehr als CAPACITY im Puffer liegen. */
    private void removeOldestWhenFull() {
        if (messages.size() <= CAPACITY) {
            return;
        }
        Iterator<UUID> idsInOrder = messages.keySet().iterator();
        UUID oldestId = idsInOrder.next();
        messages.remove(oldestId);
    }

    /** Ist diese ID im Puffer? Damit erkennt die Instanz eine doppelte Lieferung. */
    public boolean contains(UUID id) {
        return messages.containsKey(id);
    }

    /** Wie viele Nachrichten liegen gerade im Puffer? */
    public int size() {
        return messages.size();
    }

    /**
     * Darf dieser Cursor für einen Replay benutzt werden? Er muss im Puffer
     * liegen UND zum angefragten Raum gehören. Sonst antwortet der Endpunkt
     * mit 409, statt eine Lücke still zu verschweigen (Spezifikation 4.6).
     */
    public boolean knowsCursor(UUID cursorId, UUID roomId) {
        ChatMessage cursor = messages.get(cursorId);
        if (cursor == null) {
            return false;
        }
        return cursor.roomId().equals(roomId);
    }

    /**
     * Liefert alles, was NACH dem Cursor eintraf und zum selben Raum gehört,
     * in Empfangsreihenfolge. Der Cursor selbst ist nicht dabei: der Client
     * hat ihn ja schon.
     *
     * Ist der Cursor unbekannt, kommt eine leere Liste. Wer den Unterschied
     * zu «nichts verpasst» braucht, fragt vorher knowsCursor.
     */
    public List<ChatMessage> messagesAfter(UUID cursorId, UUID roomId) {
        List<ChatMessage> result = new ArrayList<>();
        boolean cursorPassed = false;
        for (ChatMessage message : messages.values()) {
            if (cursorPassed && message.roomId().equals(roomId)) {
                result.add(message);
            }
            if (message.id().equals(cursorId)) {
                cursorPassed = true;
            }
        }
        return result;
    }
}
