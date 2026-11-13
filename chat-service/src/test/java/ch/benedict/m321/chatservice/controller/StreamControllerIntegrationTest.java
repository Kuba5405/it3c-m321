package ch.benedict.m321.chatservice.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prüft GET /stream gegen einen echten Server und einen echten RabbitMQ:
 * Eingabeprüfung (400, 409), das Format der Ereignisse und die Neuverbindung
 * mit Last-Event-ID (Spezifikation 2.2 und 4.6, Szenarien S3 und S7).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class StreamControllerIntegrationTest {

    /** Setzt host und port der Anwendung automatisch auf den Container. */
    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    /** Liest ein Feld eines JSON-Objekts als Text, damit die Tests ohne verschachtelte Aufrufe auskommen. */
    private String textOf(JsonNode data, String field) {
        JsonNode node = data.get(field);
        return node.asText();
    }

    /** Liest die n-te data:-Zeile eines Clients als JSON. */
    private JsonNode dataOf(SseTestClient client, int index) throws Exception {
        String line = client.dataLines.get(index);
        return objectMapper.readTree(line);
    }

    /** Schickt eine Nachricht wie das Gateway: POST /messages. */
    private void post(UUID roomId, String senderId, String content) throws Exception {
        String json = "{\"roomId\":\"" + roomId + "\",\"senderId\":\"" + senderId
                + "\",\"senderName\":\"Anna Muster\",\"content\":\"" + content + "\"}";
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/messages"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpClient httpClient = HttpClient.newHttpClient();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(202, response.statusCode());
    }

    /** Öffnet einen Stream und gibt nur den Statuscode zurück, für die Fehlerfälle. */
    private int statusOf(String query, String lastEventId) throws Exception {
        SseTestClient client = SseTestClient.connect(port, query, lastEventId);
        int status = client.status;
        client.close();
        return status;
    }

    /** Ohne roomId weiss der Dienst nicht, welchen Raum der Client meint. */
    @Test
    void missingRoomIdIsBadRequest() throws Exception {
        int status = statusOf("", null);

        assertEquals(400, status);
    }

    /** Eine roomId, die keine UUID ist, ist ein Fehler des Clients. */
    @Test
    void roomIdThatIsNotAUuidIsBadRequest() throws Exception {
        int status = statusOf("roomId=nicht-eine-uuid", null);

        assertEquals(400, status);
    }

    /** Eine Last-Event-ID, die keine UUID ist, ebenso. */
    @Test
    void lastEventIdThatIsNotAUuidIsBadRequest() throws Exception {
        UUID room = UUID.randomUUID();
        String query = "roomId=" + room;
        int status = statusOf(query, "kaputt");

        assertEquals(400, status);
    }

    /** Eine gültige, aber unbekannte Last-Event-ID ergibt 409: eine Lücke wird gemeldet, nicht verschwiegen. */
    @Test
    void unknownLastEventIdIsConflict() throws Exception {
        UUID room = UUID.randomUUID();
        UUID unknownCursor = UUID.randomUUID();
        String query = "roomId=" + room;
        String cursorText = unknownCursor.toString();
        int status = statusOf(query, cursorText);

        assertEquals(409, status);
    }

    /** Ohne Cursor: 200 und der richtige Content-Type. */
    @Test
    void connectionWithoutCursorIsOkAndAnEventStream() throws Exception {
        UUID room = UUID.randomUUID();
        String query = "roomId=" + room;
        SseTestClient client = SseTestClient.connect(port, query, null);

        assertEquals(200, client.status);
        assertTrue(client.contentType.startsWith("text/event-stream"), client.contentType);
        client.close();
    }

    /** Ende zu Ende: POST, dann id: und data: mit den sechs Feldern des Vertrags. */
    @Test
    void postedMessageArrivesAsEventWithAllSixFields() throws Exception {
        UUID room = UUID.randomUUID();
        SseTestClient client = SseTestClient.connect(port, "roomId=" + room, null);

        post(room, "anna", "Hallo");
        client.waitForEvents(1);

        int eventCount = client.ids.size();
        String eventId = client.ids.get(0);
        JsonNode data = dataOf(client, 0);
        String sentAt = textOf(data, "sentAt");
        int fieldCount = data.size();
        assertEquals(1, eventCount);
        assertEquals(eventId, textOf(data, "id"));
        String expectedRoom = room.toString();
        assertEquals(expectedRoom, textOf(data, "roomId"));
        assertEquals("anna", textOf(data, "senderId"));
        assertEquals("Anna Muster", textOf(data, "senderName"));
        assertEquals("Hallo", textOf(data, "content"));
        assertTrue(sentAt.endsWith("Z"), sentAt);
        assertEquals(6, fieldCount);
        client.close();
    }

    /** Ein Client in einem anderen Raum bekommt nichts (S3). */
    @Test
    void clientInAnotherRoomReceivesNothing() throws Exception {
        UUID roomA = UUID.randomUUID();
        UUID roomB = UUID.randomUUID();
        SseTestClient clientA = SseTestClient.connect(port, "roomId=" + roomA, null);
        SseTestClient clientB = SseTestClient.connect(port, "roomId=" + roomB, null);

        post(roomA, "anna", "nur für A");
        post(roomB, "bert", "Kontrolle für B");
        clientA.waitForEvents(1);
        clientB.waitForEvents(1);

        assertEquals(1, clientA.ids.size());
        assertEquals(1, clientB.ids.size());
        JsonNode dataB = dataOf(clientB, 0);
        assertEquals("Kontrolle für B", textOf(dataB, "content"));
        clientA.close();
        clientB.close();
    }

    /**
     * Neuverbindungstest (S7): Client liest eine Nachricht, trennt, 50 weitere
     * werden gesendet, die Neuverbindung mit Last-Event-ID liefert genau diese
     * 50 in Reihenfolge, keine doppelt, danach kommt eine neue live.
     */
    @Test
    void reconnectDeliversExactlyTheMissedMessages() throws Exception {
        UUID room = UUID.randomUUID();
        SseTestClient first = SseTestClient.connect(port, "roomId=" + room, null);
        post(room, "anna", "Nachricht 0");
        first.waitForEvents(1);
        String cursor = first.ids.get(0);
        first.close();

        for (int i = 1; i <= 50; i++) {
            post(room, "anna", "Nachricht " + i);
        }
        SseTestClient second = SseTestClient.connect(port, "roomId=" + room, cursor);
        assertEquals(200, second.status);
        second.waitForEvents(50);

        assertEquals(50, second.ids.size());
        List<String> contents = new ArrayList<>();
        for (String dataLine : second.dataLines) {
            JsonNode data = objectMapper.readTree(dataLine);
            String content = textOf(data, "content");
            contents.add(content);
        }
        for (int i = 0; i < 50; i++) {
            String expectedContent = "Nachricht " + (i + 1);
            String actualContent = contents.get(i);
            assertEquals(expectedContent, actualContent);
        }
        boolean cursorRepeated = second.ids.contains(cursor);
        assertFalse(cursorRepeated, "der Cursor selbst wird nicht wiederholt");

        post(room, "anna", "live danach");
        second.waitForEvents(51);
        assertEquals(51, second.ids.size());
        JsonNode last = dataOf(second, 50);
        assertEquals("live danach", textOf(last, "content"));
        second.close();
    }
}
