package dev.reception.common.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liveness for humans and for the container healthcheck.
 *
 * <p>Reports the two dependencies the system cannot run without: the database and the mail
 * transport. It reports them by <em>using</em> them — a connection is opened and the SMTP handshake
 * is performed — rather than by reporting that a bean exists.
 *
 * <p>Deliberately hand-written rather than exposing Actuator: Actuator's health payload leaks
 * component internals, and this endpoint is reachable without authentication through the public
 * origin.
 */
@RestController
@RequestMapping("/health")
@Tag(name = "Health")
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    private static final String UP = "UP";
    private static final String DOWN = "DOWN";

    private final DataSource dataSource;
    private final JavaMailSenderImpl mailSender;

    public HealthController(DataSource dataSource, JavaMailSenderImpl mailSender) {
        this.dataSource = dataSource;
        this.mailSender = mailSender;
    }

    @GetMapping
    @Operation(summary = "Report database and mail connectivity")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, String> components = new LinkedHashMap<>();
        components.put("database", checkDatabase());
        components.put("mail", checkMail());

        boolean allUp = components.values().stream().allMatch(UP::equals);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", allUp ? UP : DOWN);
        body.put("components", components);

        return ResponseEntity.status(allUp ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(body);
    }

    private String checkDatabase() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(2) ? UP : DOWN;
        } catch (Exception e) {
            log.warn("Database health check failed", e);
            return DOWN;
        }
    }

    private String checkMail() {
        try {
            mailSender.testConnection();
            return UP;
        } catch (Exception e) {
            log.warn("Mail health check failed", e);
            return DOWN;
        }
    }
}
