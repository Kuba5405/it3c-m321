package ch.benedict.m321.batchwriter.service;

/**
 * Sagt: diese Nachricht können wir nicht speichern, und zwar aus einem Grund,
 * der an der Nachricht selbst liegt (kein JSON, Pflichtfeld fehlt, ...).
 *
 * Absichtlich eine geprüfte Exception: wer parse() aufruft, MUSS sich
 * überlegen, was mit einer kaputten Nachricht geschieht. Sie verschwindet
 * nicht stillschweigend.
 */
public class InvalidMessageException extends Exception {

    /**
     * Legt die Ausnahme mit einer kurzen Begründung an. Die Begründung
     * landet später im Header x-error-reason der Dead-Letter-Queue.
     */
    public InvalidMessageException(String reason) {
        super(reason);
    }
}
