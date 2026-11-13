package ch.benedict.m321.chatservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Prüft, dass der Spring-Kontext überhaupt hochfährt.
 * Ein RabbitMQ wird dafür nicht gebraucht: die Verbindung wird erst
 * beim ersten Senden aufgebaut, nicht beim Start.
 */
@SpringBootTest
class ChatServiceApplicationTest {

    /** Prüft, dass die ganze Anwendung startet. Fehlt eine Bean oder stimmt die Konfiguration nicht, scheitert schon dieser Test. */
    @Test
    void contextLoads() {
        // Kein Assert nötig. Fährt der Kontext nicht hoch, wirft Spring
        // eine Exception und der Test wird rot.
    }
}
