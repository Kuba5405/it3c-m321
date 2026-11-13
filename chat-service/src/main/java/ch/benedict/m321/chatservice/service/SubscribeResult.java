package ch.benedict.m321.chatservice.service;

/**
 * Das Ergebnis von LiveDeliveryState.subscribe: drei klar getrennte Fälle statt
 * eines Wahrheitswerts, damit niemand «angemeldet» und «Replay gescheitert»
 * verwechseln kann.
 */
public enum SubscribeResult {

    /** Die Verbindung ist angemeldet und bekommt ab jetzt alle neuen Nachrichten ihres Raums. */
    SUBSCRIBED,

    /** Die Last-Event-ID ist gültig, aber nicht (mehr) im Puffer. Der Endpunkt antwortet mit 409. */
    UNKNOWN_CURSOR,

    /** Der Client ist beim Nachholen weggebrochen. Es wurde nichts angemeldet, die Verbindung ist beendet. */
    REPLAY_FAILED
}
