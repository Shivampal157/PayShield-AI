package com.payshield.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI payShieldOpenAPI() {
        String bearerScheme = "BearerJWT";
        return new OpenAPI()
                .info(new Info()
                        .title("PayShield AI — FinTech Payment Ledger, Risk & RAG Copilot API")
                        .version("1.0.0")
                        .description("Intelligent Payments. Explainable Risk. Reliable Ledger. " +
                                "Portfolio payment simulation platform featuring ACID double-entry ledger, " +
                                "Redis ZSET Lua sliding-window velocity controls, OpenSearch 8-D k-NN fraud scoring, " +
                                "Kafka transactional outbox, and RAG dispute investigation.")
                        .contact(new Contact().name("PayShield AI Engineering")))
                .addSecurityItem(new SecurityRequirement().addList(bearerScheme))
                .components(new Components()
                        .addSecuritySchemes(bearerScheme, new SecurityScheme()
                                .name(bearerScheme)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Short-lived JWT access token (or authenticate automatically via HttpOnly ps_access_token cookie)")));
    }
}
