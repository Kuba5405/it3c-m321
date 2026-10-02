package ch.benedict.m321.batchwriter.service;

import ch.benedict.m321.batchwriter.dto.ChatMessage;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;

/**
 * Macht aus den rohen Bytes einer Queue-Nachricht eine ChatMessage und
 * entscheidet dabei, ob die Nachricht gültig ist (Spezifikation 3.2).
 *
 * Wir lesen die Bytes selbst und ignorieren den Header __TypeId__. Der
 * zeigt auf eine Klasse des chat-service, die es hier nicht gibt, und
 * Szenario S5 schickt ihn gar nicht mit. Wer nur das JSON liest, hängt nur
 * vom Vertrag ab.
 */
@Component
public class MessageParser {

    /** Das Zeichen mit dem Code 0. PostgreSQL kann es in einem Text nicht speichern. */
    private static final char NULL_CHARACTER = 0;

    /**
     * Die Grenzen für sentAt: das Jahr 1 bis zum Ende des Jahres 9999.
     *
     * PostgreSQL speichert Zeitpunkte bis zum Jahr 294276 und lehnt alles darüber
     * ab. Eine abgelehnte Nachricht lässt den ganzen Stapel scheitern, bei jeder
     * Wiederholung erneut, und die Queue steht still. Noch schlimmer ist ein Zeitpunkt
     * weit vor dem Jahr 1: den speichert der Treiber stillschweigend als "-infinity".
     * Beides wurde bei Randfall-Versuchen am 01.10.2026 gemessen. Ein Chat braucht
     * keine Nachricht aus dem Jahr 20000, also lehnen wir sie vor der Datenbank ab.
     */
    private static final Instant EARLIEST_SENT_AT = Instant.parse("0001-01-01T00:00:00Z");
    private static final Instant LATEST_SENT_AT = Instant.parse("9999-12-31T23:59:59.999999999Z");

    private final ObjectMapper objectMapper;

    /**
     * Baut den JSON-Leser mit genau den Einstellungen, die wir brauchen,
     * statt uns auf die Voreinstellung von Spring zu verlassen. So verhält
     * sich der Einheitstest genauso wie der laufende Dienst.
     */
    public MessageParser() {
        JsonMapper.Builder builder = JsonMapper.builder();
        builder.addModule(new JavaTimeModule());
        // Ein Sender darf ein Feld ergänzen, ohne den Schreibweg zu brechen.
        builder.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.objectMapper = builder.build();
    }

    /**
     * Liest und prüft eine Nachricht. Gibt sie nur zurück, wenn alle sechs
     * Felder da und brauchbar sind, sonst wirft sie InvalidMessageException.
     */
    public ChatMessage parse(byte[] body) throws InvalidMessageException {
        ChatMessage message = readJson(body);
        checkFields(message);
        return message;
    }

    /**
     * Wandelt die Bytes in eine ChatMessage um. Alles, was Jackson nicht
     * lesen kann (kein JSON, falsche UUID, falscher Zeitstempel), wird zu
     * einer einzigen, verständlichen Ausnahme.
     *
     * Die Meldung nennt nur die Art des Fehlers, nicht den Inhalt: der
     * Nachrichtentext gehört nicht in ein Log.
     */
    private ChatMessage readJson(byte[] body) throws InvalidMessageException {
        ChatMessage message;
        try {
            message = objectMapper.readValue(body, ChatMessage.class);
        } catch (IOException exception) {
            Class<?> errorClass = exception.getClass();
            String errorType = errorClass.getSimpleName();
            throw new InvalidMessageException("not a readable chat message (" + errorType + ")");
        }

        if (message == null) {
            throw new InvalidMessageException("body is JSON null, not a chat message");
        }
        return message;
    }

    /**
     * Prüft Feld für Feld, in der Reihenfolge des JSON, damit die erste
     * Fehlermeldung immer dieselbe ist.
     */
    private void checkFields(ChatMessage message) throws InvalidMessageException {
        requirePresent(message.id(), "id");
        requirePresent(message.roomId(), "roomId");
        requireText(message.senderId(), "senderId");
        requireText(message.senderName(), "senderName");
        requireText(message.content(), "content");
        requirePresent(message.sentAt(), "sentAt");
        requireSentAtInRange(message.sentAt());
    }

    /**
     * Der Zeitpunkt muss zwischen EARLIEST_SENT_AT und LATEST_SENT_AT liegen.
     * Warum, steht bei den beiden Konstanten oben.
     */
    private void requireSentAtInRange(Instant sentAt) throws InvalidMessageException {
        boolean tooEarly = sentAt.isBefore(EARLIEST_SENT_AT);
        boolean tooLate = sentAt.isAfter(LATEST_SENT_AT);
        if (tooEarly || tooLate) {
            throw new InvalidMessageException("field 'sentAt' is out of range (year 1 to 9999)");
        }
    }

    /**
     * Ein Feld, das kein Text ist (UUID, Zeitpunkt), darf einfach nicht fehlen.
     */
    private void requirePresent(Object value, String fieldName) throws InvalidMessageException {
        if (value == null) {
            throw new InvalidMessageException("field '" + fieldName + "' is missing");
        }
    }

    /**
     * Ein Textfeld muss da sein, darf nicht nur aus Leerzeichen bestehen und
     * nicht das Zeichen NUL enthalten.
     */
    private void requireText(String value, String fieldName) throws InvalidMessageException {
        requirePresent(value, fieldName);

        if (value.isBlank()) {
            throw new InvalidMessageException("field '" + fieldName + "' must not be blank");
        }

        int nullPosition = value.indexOf(NULL_CHARACTER);
        if (nullPosition >= 0) {
            throw new InvalidMessageException("field '" + fieldName + "' contains the NUL character");
        }
    }
}
