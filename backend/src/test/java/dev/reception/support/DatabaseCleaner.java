package dev.reception.support;

import java.util.List;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Empties every application table between tests that count rows.
 *
 * <p>Transactional rollback is the isolation mechanism for most integration tests, but it does not
 * apply to a test that drives the application over real HTTP: the request runs on the server's own
 * thread, in its own transaction, and commits. Those tests need the database actually emptied.
 *
 * <p>The table list is discovered rather than written down, so a phase that adds a table does not
 * also have to remember to add it here — the failure mode of a hand-maintained list is a test that
 * passes for the wrong reason.
 */
@Component
public class DatabaseCleaner {

    /** Flyway's own bookkeeping. Truncating it would make the next context load re-run migrations. */
    private static final String FLYWAY_HISTORY = "flyway_schema_history";

    private final JdbcTemplate jdbc;

    public DatabaseCleaner(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void clean() {
        List<String> tables = jdbc.queryForList(
                        """
                        select table_name from information_schema.tables
                         where table_schema = 'public' and table_type = 'BASE TABLE'
                        """,
                        String.class)
                .stream()
                .filter(table -> !FLYWAY_HISTORY.equals(table))
                .toList();

        if (tables.isEmpty()) {
            return;
        }
        // One statement with CASCADE, so foreign keys do not dictate a deletion order that would
        // have to be maintained by hand.
        jdbc.execute("truncate table "
                + tables.stream().map(table -> "public." + table).collect(Collectors.joining(", "))
                + " restart identity cascade");
    }
}
