package ch.benedict.m321.batchwriter.config;

/**
 * Die Namen der beiden Queues an genau EINER Stelle.
 *
 * Sie werden beim Anlegen, beim Verbraucher und im Test gebraucht. Ein
 * Tippfehler in einem String wäre sonst erst zur Laufzeit sichtbar, und
 * zwar als "es kommt nie etwas an".
 *
 * Die Namen sind der Vertrag mit dem chat-service. Der hat seine eigene
 * Kopie dieser Klasse, denn die Dienste teilen keinen Code.
 */
public final class QueueNames {

    /** Schreibweg: hier holt der batch-writer die Nachrichten ab. */
    public static final String PERSIST_QUEUE = "chat.persist";

    /** Dead Letter: hier landet, was der batch-writer nicht speichern kann. */
    public static final String DEAD_LETTER_QUEUE = "chat.dlq";

    /**
     * Diese Klasse ist eine reine Namenssammlung und wird nie erzeugt.
     */
    private QueueNames() {
    }
}
