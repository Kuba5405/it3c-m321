package ch.benedict.m321.batchwriter.service;

import ch.benedict.m321.batchwriter.dto.ChatMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Der einzige Ort im Dienst, der in die Datenbank schreibt.
 *
 * Hier entscheidet sich, ob die Datenbank die Last aushält: ein ganzer
 * Stapel Nachrichten geht in EINER Transaktion hinein, statt jede
 * Nachricht in einer eigenen (PLANUNG.md 4.1).
 */
@Repository
@RequiredArgsConstructor
public class MessageRepository {

    /**
     * ON CONFLICT (id) DO NOTHING: gibt es die id schon, wird die Zeile
     * übersprungen. Das macht Duplikate harmlos. Weil die Prüfung im INSERT
     * selbst passiert, kann sich keine zweite Instanz dazwischendrängen,
     * wie es bei "erst SELECT, dann INSERT" möglich wäre.
     */
    private static final String INSERT_SQL = "INSERT INTO message "
            + "(id, room_id, sender_id, sender_name, content, sent_at) "
            + "VALUES (?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT (id) DO NOTHING";

    private final JdbcTemplate jdbcTemplate;

    /**
     * Schreibt alle Nachrichten in einer einzigen Transaktion und gibt zurück,
     * wie viele davon NEU waren. Der Rest waren Duplikate.
     *
     * @Transactional heisst: Spring beginnt die Transaktion vor dieser Methode
     * und macht COMMIT, wenn sie normal endet. Wirft sie eine Exception, macht
     * Spring ROLLBACK, und vom Stapel bleibt nichts zurück. Alles oder nichts.
     */
    @Transactional
    public int saveAll(List<ChatMessage> messages) {
        if (messages.isEmpty()) {
            return 0;
        }

        List<Object[]> rows = toRows(messages);
        int[] updateCounts = jdbcTemplate.batchUpdate(INSERT_SQL, rows);

        return countInserted(updateCounts);
    }

    /**
     * Macht aus jeder Nachricht eine Zeile Parameter für das INSERT, in der
     * Reihenfolge der Platzhalter.
     *
     * Der Zeitpunkt geht als OffsetDateTime mit Zone UTC hinein: der
     * PostgreSQL-Treiber kennt ihn für timestamptz und es gibt keine
     * Umrechnung über die Zeitzone des Rechners.
     */
    private List<Object[]> toRows(List<ChatMessage> messages) {
        List<Object[]> rows = new ArrayList<>();
        for (ChatMessage message : messages) {
            OffsetDateTime sentAt = OffsetDateTime.ofInstant(message.sentAt(), ZoneOffset.UTC);
            Object[] row = {
                    message.id(),
                    message.roomId(),
                    message.senderId(),
                    message.senderName(),
                    message.content(),
                    sentAt
            };
            rows.add(row);
        }
        return rows;
    }

    /**
     * Zählt die Zeilen, die wirklich eingefügt wurden. Die Datenbank meldet
     * pro Zeile 1 für eingefügt und 0 für "gab es schon" (DO NOTHING).
     */
    private int countInserted(int[] updateCounts) {
        int inserted = 0;
        for (int i = 0; i < updateCounts.length; i++) {
            inserted = inserted + updateCounts[i];
        }
        return inserted;
    }
}
