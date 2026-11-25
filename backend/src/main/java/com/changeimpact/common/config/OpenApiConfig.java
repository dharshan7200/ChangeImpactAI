package com.changeimpact.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 3 / Swagger documentation configuration.
 *
 * <p>Configures JWT Bearer authentication scheme so developers and QA can
 * authenticate directly from the Swagger UI interface.
 */
@Configuration
public class OpenApiConfig {

    private static final String SECURITY_SCHEME_NAME = "BearerAuth";

    @Bean
    public OpenAPI changeImpactOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("ChangeImpact AI — API Documentation")
                        .description("AI-Powered Code Change Impact Analysis and Intelligent Developer Assistant")
                        .version("0.1.0-SNAPSHOT")
                        .contact(new Contact()
                                .name("ChangeImpact AI Engineering Team")
                                .email("engineering@changeimpact.ai"))
                        .license(new License()
                                .name("Apache 2.0")
                                .url("https://www.apache.org/licenses/LICENSE-2.0")))
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME))
                .components(new Components()
                        .addSecuritySchemes(SECURITY_SCHEME_NAME,
                                new SecurityScheme()
                                        .name(SECURITY_SCHEME_NAME)
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description("Enter your JWT access token retrieved from /api/v1/auth/login")));
    }
}

