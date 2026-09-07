package dev.reception.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Serves the API description at {@code /api/docs} (springdoc paths are set in application.yml). */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI receptionOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Reception API")
                        .version("0.1.0")
                        .description(
                                """
                                Multi-tenant appointment booking.

                                No endpoint anywhere accepts `business_id` from the caller: the tenant is \
                                derived from the authenticated Membership, the slug in a public path, or the \
                                conversation record. Errors are RFC 9457 problem+json extended with a \
                                machine-readable `code`.
                                """)
                        .license(new License().name("Proprietary")));
    }
}
