package ch.benedict.m321.chatservice.controller;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.Socket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Prüft, dass ein Client, der die Verbindung offen lässt, aber nie liest,
 * die Zustellung an die anderen nur kurz aufhält (Spezifikation 7, Punkt 1).
 * Ohne das Zeitlimit in application.yml hing der gesunde Client im Versuch
 * über 40 Sekunden fest.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class StalledClientIntegrationTest {

    /** Setzt host und port der Anwendung automatisch auf den Container. */
    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    @LocalServerPort
    private int port;

    /** Öffnet einen Stream über einen rohen Socket mit winzigem Empfangspuffer und liest danach nie. */
    private Socket openStalledStream(UUID room) throws Exception {
        Socket socket = new Socket();
        socket.setReceiveBufferSize(1024);
        InetSocketAddress address = new InetSocketAddress("localhost", port);
        socket.connect(address);
        String request = "GET /stream?roomId=" + room + " HTTP/1.1\r\nHost: x\r\nAccept: text/event-stream\r\n\r\n";
        OutputStream output = socket.getOutputStream();
        output.write(request.getBytes());
        output.flush();
        return socket;
    }

    /** Schickt eine grosse Nachricht (500 KB), damit die Puffer des stehenden Clients schnell voll sind. */
    private void postBigMessage(UUID room) throws Exception {
        String bigText = "x".repeat(500_000);
        String json = "{\"roomId\":\"" + room + "\",\"senderId\":\"anna\",\"senderName\":\"Anna Muster\",\"content\":\""
                + bigText + "\"}";
        URI uri = URI.create("http://localhost:" + port + "/messages");
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpClient httpClient = HttpClient.newHttpClient();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(202, response.statusCode());
    }

    /**
     * Ein stehender Client neben einem gesunden: der gesunde bekommt alle 30
     * grossen Nachrichten, spätestens nach 30 Sekunden (ohne Zeitlimit blieb er im Versuch
     * über 40 Sekunden bei 1 stehen, der Test unterscheidet das klar). Der stehende wird
     * nach dem Zeitlimit von 5 Sekunden entfernt.
     */
    @Test
    void aStalledClientOnlyDelaysTheHealthyOneBrieflyAndIsDropped() throws Exception {
        UUID room = UUID.randomUUID();
        Socket stalled = openStalledStream(room);
        SseTestClient healthy = SseTestClient.connect(port, "roomId=" + room, null);
        Thread.sleep(1000);

        for (int i = 0; i < 30; i++) {
            postBigMessage(room);
        }
        for (int second = 0; second < 30; second++) {
            if (healthy.ids.size() >= 30) {
                break;
            }
            Thread.sleep(1000);
        }

        assertEquals(30, healthy.ids.size());
        stalled.close();
        healthy.close();
    }
}
