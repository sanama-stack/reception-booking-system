package dev.reception;

import static org.assertj.core.api.Assertions.assertThat;

import dev.reception.support.IntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class ApplicationContextTest extends IntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void flyway_applied_the_baseline_migration() {
        List<String> applied = jdbc.queryForList(
                "SELECT script FROM flyway_schema_history WHERE success = true ORDER BY installed_rank",
                String.class);

        assertThat(applied).contains("V1__extensions.sql");
    }

    @Test
    void the_extensions_the_schema_depends_on_are_installed() {
        List<String> extensions = jdbc.queryForList("SELECT extname FROM pg_extension", String.class);

        // btree_gist is the single feature the booking design rests on (ADR-0002); citext backs
        // case-insensitive email uniqueness.
        assertThat(extensions).contains("btree_gist", "citext");
    }
}
