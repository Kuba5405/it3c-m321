package ch.benedict.m321.chatservice.controller;

import ch.benedict.m321.chatservice.ChatServiceApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Bildet Szenario S3 im Kleinen nach: ZWEI chat-service-Instanzen an EINEM
 * echten RabbitMQ. Ein Client hängt an Instanz 1, ein zweiter an Instanz 2,
 * beide im selben Raum. Jede Nachricht muss BEIDE erreichen, egal an welcher
 * Instanz sie angenommen wurde (Spezifikation 4.2).
 */
@Testcontainers
class TwoInstancesIntegrationTest {

    /** Der gemeinsame Broker beider Instanzen. */
    @Container
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    private static ConfigurableApplicationContext instanceOne;
    private static ConfigurableApplicationContext instanceTwo;
    private static int portOne;
    private static int portTwo;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Startet eine komplette Anwendung auf einem freien Port, verbunden mit dem Test-Broker. */
    private static ConfigurableApplicationContext startInstance() {
        SpringApplicationBuilder builder = new SpringApplicationBuilder(ChatServiceApplication.class);
        // Kommandozeilen-Argumente haben Vorrang vor application.yml. Mit
        // builder.properties() würde die Datei sie überstimmen (Port 8080 wäre doppelt belegt).
        String[] arguments = {
                "--server.port=0",
                "--spring.rabbitmq.host=" + rabbitMq.getHost(),
                "--spring.rabbitmq.port=" + rabbitMq.getAmqpPort(),
                "--spring.rabbitmq.username=" + rabbitMq.getAdminUsername(),
                "--spring.rabbitmq.password=" + rabbitMq.getAdminPassword()};
        return builder.run(arguments);
    }

    /** Liest den zufällig gewählten Port einer laufenden Instanz. */
    private static int portOf(ConfigurableApplicationContext context) {
        String port = context.getEnvironment().getProperty("local.server.port");
        return Integer.parseInt(port);
    }

    /** Startet beide Instanzen einmal für alle Tests dieser Klasse. */
    @BeforeAll
    static void startBothInstances() {
        instanceOne = startInstance();
        instanceTwo = startInstance();
        portOne = portOf(instanceOne);
        portTwo = portOf(instanceTwo);
    }

    /** Fährt beide Instanzen herunter, damit keine Verbindung offen bleibt. */
    @AfterAll
    static void stopBothInstances() {
        instanceOne.close();
        instanceTwo.close();
    }

    /** Schickt eine Nachricht an genau eine Instanz, wie das Gateway es täte. */
    private void post(int port, UUID roomId, String senderId, String content) throws Exception {
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

    /** Liest den Text der Nachrichten eines Clients in der Reihenfolge des Eintreffens. */
    private List<String> contentsOf(SseTestClient client) throws Exception {
        List<String> contents = new ArrayList<>();
        List<String> lines = new ArrayList<>(client.dataLines);
        for (String line : lines) {
            JsonNode data = objectMapper.readTree(line);
            contents.add(data.get("content").asText());
        }
        return contents;
    }

    /**
     * Je 20 Nachrichten über jede Instanz in Raum A, mit eigenem Absender pro
     * Zielinstanz und laufender Nummer. Beide Clients in Raum A bekommen alle 40,
     * jede genau einmal und pro Absender in der gesendeten Reihenfolge. Der
     * Client in Raum B bekommt nur seine eigene Kontrollnachricht.
     */
    @Test
    void bothClientsOfTheRoomReceiveEveryMessageOnceAndInOrder() throws Exception {
        UUID roomA = UUID.randomUUID();
        UUID roomB = UUID.randomUUID();
        SseTestClient clientOne = SseTestClient.connect(portOne, "roomId=" + roomA, null);
        SseTestClient clientTwo = SseTestClient.connect(portTwo, "roomId=" + roomA, null);
        SseTestClient clientB = SseTestClient.connect(portTwo, "roomId=" + roomB, null);

        for (int i = 0; i < 20; i++) {
            post(portOne, roomA, "sender-one", "one-" + i);
            post(portTwo, roomA, "sender-two", "two-" + i);
        }
        post(portOne, roomB, "sender-b", "control-b");
        clientOne.waitForEvents(40);
        clientTwo.waitForEvents(40);
        clientB.waitForEvents(1);

        assertBothSendersInOrderExactlyOnce(contentsOf(clientOne));
        assertBothSendersInOrderExactlyOnce(contentsOf(clientTwo));
        assertEquals(40, new HashSet<>(clientOne.ids).size());
        assertEquals(40, new HashSet<>(clientTwo.ids).size());
        assertEquals(List.of("control-b"), contentsOf(clientB));
        clientOne.close();
        clientTwo.close();
        clientB.close();
    }

    /**
     * Prüft: genau 40 Nachrichten, und für jeden der beiden Absender steigen
     * die Nummern 0 bis 19 ohne Lücke und ohne Vertauschung.
     */
    private void assertBothSendersInOrderExactlyOnce(List<String> contents) {
        assertEquals(40, contents.size());
        List<String> fromSenderOne = new ArrayList<>();
        List<String> fromSenderTwo = new ArrayList<>();
        for (String content : contents) {
            if (content.startsWith("one-")) {
                fromSenderOne.add(content);
            } else {
                fromSenderTwo.add(content);
            }
        }
        List<String> expectedOne = new ArrayList<>();
        List<String> expectedTwo = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            expectedOne.add("one-" + i);
            expectedTwo.add("two-" + i);
        }
        assertEquals(expectedOne, fromSenderOne);
        assertEquals(expectedTwo, fromSenderTwo);
        Set<String> distinct = new HashSet<>(contents);
        assertEquals(40, distinct.size());
    }
}
