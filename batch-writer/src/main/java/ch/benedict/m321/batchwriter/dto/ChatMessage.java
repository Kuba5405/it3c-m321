package ch.benedict.m321.batchwriter.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Eine Nachricht, wie sie der chat-service in die Queue chat.persist legt.
 *
 * Der Vertrag ist das JSON, nicht diese Klasse. Der batch-writer hat darum
 * seine EIGENE Kopie und teilt keinen Code mit dem chat-service. Ein
 * gemeinsames Modul würde die Dienste aneinanderbinden.
 *
 * @param id         vom chat-service vergebene Kennung, zugleich Primärschlüssel
 * @param roomId     der Raum, in den die Nachricht gehört
 * @param senderId   die sub-Kennung des Absenders aus Keycloak
 * @param senderName der Anzeigename des Absenders
 * @param content    der Text der Nachricht
 * @param sentAt     der vom Server gesetzte Zeitpunkt
 */
public record ChatMessage(
        UUID id,
        UUID roomId,
        String senderId,
        String senderName,
        String content,
        Instant sentAt) {
}
