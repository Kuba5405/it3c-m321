package ch.benedict.m321.chatservice.service;

import ch.benedict.m321.chatservice.dto.ChatMessage;

/**
 * Eine Nachricht so, wie sie im Replay-Puffer liegt: zusammen mit dem JSON,
 * das als data: hinausgeht.
 *
 * Warum beides zusammen: das JSON entsteht EINMAL, wenn die Nachricht vom
 * Broker kommt. Live-Zustellung und spätere Replays schicken danach genau
 * diesen Text, niemand muss die Nachricht ein zweites Mal umwandeln.
 *
 * @param message die Nachricht selbst (für ID, Raum und Grössenrechnung)
 * @param json    das fertige JSON für die data:-Zeile
 */
public record BufferedMessage(ChatMessage message, String json) {
}
