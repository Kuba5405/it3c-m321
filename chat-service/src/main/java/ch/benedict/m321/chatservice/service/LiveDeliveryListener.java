package ch.benedict.m321.chatservice.service;

import ch.benedict.m321.chatservice.dto.ChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Der eine Consumer dieser Instanz: er liest die Instanzqueue und gibt jede
 * Nachricht an LiveDeliveryState weiter.
 *
 * Warum so klein: alles Wichtige (Raumfilter, Dublettenprüfung, Replay) liegt
 * in LiveDeliveryState und ist dort ohne Broker testbar. Dieser Listener ist
 * nur der Draht zwischen Broker und Zustand.
 *
 * Bestätigt (ACK) wird, wenn die Methode normal endet, also NACH der Übergabe
 * an den Zustand. Ein Client, der nicht erreichbar ist, wird in
 * LiveDeliveryState abgefangen und ist darum kein Grund für ein Requeue.
 * Genau ein Consumer (Standard von Spring), damit die Reihenfolge einer Queue
 * erhalten bleibt (Spezifikation 4.2, E9).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class LiveDeliveryListener {

    private final LiveDeliveryState liveDeliveryState;

    /**
     * Wird für jede Nachricht der Instanzqueue aufgerufen. Der Queue-Name
     * wird beim Start erzeugt, darum liest ihn "#{liveQueue.name}" aus der
     * Bean liveQueue statt aus einer Konstante.
     */
    @RabbitListener(queues = "#{liveQueue.name}")
    public void onMessage(ChatMessage message) {
        log.debug("Message {} received from live queue, room {}", message.id(), message.roomId());
        liveDeliveryState.deliver(message);
    }
}
