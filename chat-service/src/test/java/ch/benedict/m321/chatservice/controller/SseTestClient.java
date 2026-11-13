package ch.benedict.m321.chatservice.controller;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Ein kleiner SSE-Client für Tests: öffnet GET /stream, liest die Zeilen
 * in einem eigenen Thread mit und merkt sich alle id:- und data:-Zeilen.
 *
 * Warum selbst gebaut: der Test soll genau sehen, was über die Leitung
 * kommt, nicht was eine Bibliothek daraus macht. Er benutzt bewusst keine
 * Streams (Regel aus CLAUDE.md), sondern einen BufferedReader.
 */
class SseTestClient implements AutoCloseable {

    final int status;
    final String contentType;
    final List<String> ids = Collections.synchronizedList(new ArrayList<>());
    final List<String> dataLines = Collections.synchronizedList(new ArrayList<>());
    private final InputStream body;

    /** Nur über connect() erzeugen, dort wird die Antwort schon gelesen. */
    private SseTestClient(HttpResponse<InputStream> response) {
        this.status = response.statusCode();
        this.contentType = response.headers().firstValue("Content-Type").orElse("");
        this.body = response.body();
    }

    /**
     * Öffnet die Verbindung und wartet auf den Antwortkopf. Bei Status 200
     * liest danach ein Hintergrund-Thread die Ereignisse mit.
     *
     * @param query         alles hinter dem Fragezeichen, zum Beispiel "roomId=..."
     * @param lastEventId   Wert für den Header Last-Event-ID oder null
     */
    static SseTestClient connect(int port, String query, String lastEventId) throws Exception {
        URI uri = URI.create("http://localhost:" + port + "/stream?" + query);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).header("Accept", "text/event-stream")
                .timeout(Duration.ofSeconds(10));
        if (lastEventId != null) {
            builder.header("Last-Event-ID", lastEventId);
        }
        HttpRequest request = builder.build();
        HttpClient httpClient = HttpClient.newHttpClient();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        SseTestClient client = new SseTestClient(response);
        if (client.status == 200) {
            client.startReading();
        }
        return client;
    }

    /** Startet den Thread, der Zeile für Zeile liest, bis die Verbindung zugeht. */
    private void startReading() {
        Thread reader = new Thread(new Runnable() {
            /** Liest alle Zeilen und sortiert id: und data: in die beiden Listen. */
            @Override
            public void run() {
                try {
                    BufferedReader lines = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
                    String line = lines.readLine();
                    while (line != null) {
                        sortLine(line);
                        line = lines.readLine();
                    }
                } catch (IOException exception) {
                    // Die Verbindung wurde geschlossen: das ist das normale Ende.
                }
            }
        });
        reader.setDaemon(true);
        reader.start();
    }

    /** Merkt sich die UUID einer id:-Zeile und das JSON einer data:-Zeile. */
    private void sortLine(String line) {
        if (line.startsWith("id:")) {
            ids.add(line.substring(3).trim());
        }
        if (line.startsWith("data:")) {
            dataLines.add(line.substring(5).trim());
        }
    }

    /** Wartet bis zu 5 Sekunden, bis mindestens so viele Ereignisse da sind. */
    void waitForEvents(int expected) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (ids.size() >= expected) {
                return;
            }
            Thread.sleep(50);
        }
    }

    /** Schliesst die Verbindung, so wie ein Client, der abbricht. */
    @Override
    public void close() throws IOException {
        body.close();
    }
}
