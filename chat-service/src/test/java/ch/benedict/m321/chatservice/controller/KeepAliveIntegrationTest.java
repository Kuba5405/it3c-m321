package ch.benedict.m321.chatservice.controller;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prüft, dass das 5-Sekunden-Zeitlimit gegen stehende SSE-Clients nicht auch
 * ruhige Verbindungen des Gateways kappt (Review, Befund «connection-timeout»).
 * Tomcat benutzt connection-timeout sonst auch als Keep-Alive-Zeit: eine
 * gepoolte Verbindung, die 7 Sekunden ruht, wäre beim nächsten POST tot.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class KeepAliveIntegrationTest {

    /** Setzt host und port der Anwendung automatisch auf den Container. */
    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    @LocalServerPort
    private int port;

    /** Schickt einen POST über die offene Verbindung und liest die Statuszeile samt Antwortkörper. */
    private String postOnSocket(Socket socket, BufferedReader reader, UUID room) throws Exception {
        String body = "{\"roomId\":\"" + room + "\",\"senderId\":\"anna\",\"senderName\":\"Anna Muster\",\"content\":\"Hallo\"}";
        int length = body.getBytes(StandardCharsets.UTF_8).length;
        String request = "POST /messages HTTP/1.1\r\nHost: x\r\nContent-Type: application/json\r\nContent-Length: "
                + length + "\r\n\r\n" + body;
        OutputStream output = socket.getOutputStream();
        output.write(request.getBytes(StandardCharsets.UTF_8));
        output.flush();
        String statusLine = reader.readLine();
        boolean chunked = false;
        int contentLength = 0;
        String header = reader.readLine();
        while (header != null && !header.isEmpty()) {
            String lowerCase = header.toLowerCase();
            if (lowerCase.startsWith("content-length:")) {
                contentLength = Integer.parseInt(header.substring(15).trim());
            }
            if (lowerCase.startsWith("transfer-encoding:") && lowerCase.contains("chunked")) {
                chunked = true;
            }
            header = reader.readLine();
        }
        if (chunked) {
            readChunkedBody(reader);
        } else {
            char[] answer = new char[contentLength];
            reader.read(answer, 0, contentLength);
        }
        return statusLine;
    }

    /** Liest einen Körper in Stücken (chunked) bis zum Ende, damit die Verbindung für die nächste Antwort frei ist. */
    private void readChunkedBody(BufferedReader reader) throws Exception {
        String sizeLine = reader.readLine();
        while (!"0".equals(sizeLine.trim())) {
            int size = Integer.parseInt(sizeLine.trim(), 16);
            char[] chunk = new char[size];
            reader.read(chunk, 0, size);
            reader.readLine();
            sizeLine = reader.readLine();
        }
        reader.readLine();
    }

    /**
     * Zwei POST auf DERSELBEN Verbindung, dazwischen 7 Sekunden Ruhe (mehr als das
     * Zeitlimit von 5 s). Der zweite muss mit 202 antworten.
     */
    @Test
    void anIdleKeepAliveConnectionSurvivesLongerThanTheConnectionTimeout() throws Exception {
        UUID room = UUID.randomUUID();
        Socket socket = new Socket("localhost", port);
        InputStreamReader inputReader = new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8);
        BufferedReader reader = new BufferedReader(inputReader);

        String first = postOnSocket(socket, reader, room);
        Thread.sleep(7000);
        String second = postOnSocket(socket, reader, room);

        assertTrue(first.contains("202"), first);
        assertEquals(first, second);
        socket.close();
    }
}
