package ch.benedict.m321.batchwriter.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Prüft, dass unsinnige Einstellungen den Start verhindern, statt den Dienst
 * unbemerkt in einen schlechten Zustand zu bringen. Ohne Broker und ohne Datenbank.
 */
class RabbitConfigTest {

    /**
     * BATCH_TIMEOUT_MS=0 hiesse "gar nicht warten": der Verbraucher fragte in einer
     * Endlosschleife nach Nachrichten und liefe im Leerlauf mit 100 Prozent CPU.
     * Gemessen am 01.10.2026 mit einem Container und dieser Einstellung.
     */
    @Test
    void refusesATimeoutOfZero() {
        RabbitConfig rabbitConfig = new RabbitConfig();

        try {
            rabbitConfig.batchContainerFactory(null, 500, 0);
            fail("A timeout of 0 must be refused");
        } catch (IllegalArgumentException expected) {
            String problem = expected.getMessage();
            boolean namesTheVariable = problem.contains("BATCH_TIMEOUT_MS");
            assertTrue(namesTheVariable, problem);
        }
    }

    /**
     * Auch ein negativer Wert ist unsinnig und wird mit der Variable im Text abgelehnt,
     * damit die Fehlermeldung sagt, WAS zu ändern ist.
     */
    @Test
    void refusesANegativeTimeout() {
        RabbitConfig rabbitConfig = new RabbitConfig();

        try {
            rabbitConfig.batchContainerFactory(null, 500, -5);
            fail("A negative timeout must be refused");
        } catch (IllegalArgumentException expected) {
            String problem = expected.getMessage();
            boolean namesTheVariable = problem.contains("BATCH_TIMEOUT_MS");
            assertTrue(namesTheVariable, problem);
        }
    }

    /**
     * Eine Stapelgrösse unter 1 lehnt Spring selbst ab, aber mit einer Meldung ohne
     * Variablennamen. Wir prüfen vorher und sagen, welche Einstellung falsch ist.
     */
    @Test
    void refusesABatchSizeOfZero() {
        RabbitConfig rabbitConfig = new RabbitConfig();

        try {
            rabbitConfig.batchContainerFactory(null, 0, 200);
            fail("A batch size of 0 must be refused");
        } catch (IllegalArgumentException expected) {
            String problem = expected.getMessage();
            boolean namesTheVariable = problem.contains("BATCH_SIZE");
            assertTrue(namesTheVariable, problem);
        }
    }
}
