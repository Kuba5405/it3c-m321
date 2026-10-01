package ch.benedict.m321.batchwriter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Startpunkt des batch-writer.
 *
 * Dieser Dienst ist der einzige Schreiber in die Datenbank. Er holt
 * Nachrichten aus der Queue chat.persist und speichert sie stapelweise.
 * Er hat keinen Webserver und keinen Port: seine einzige Schnittstelle
 * ist die Queue.
 */
@SpringBootApplication
public class BatchWriterApplication {

    /**
     * Startet den Spring-Kontext. Ab hier läuft alles von selbst.
     */
    public static void main(String[] args) {
        SpringApplication.run(BatchWriterApplication.class, args);
    }
}
