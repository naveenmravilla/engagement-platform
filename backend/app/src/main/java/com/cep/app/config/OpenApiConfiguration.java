package com.cep.app.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {
    @Bean
    OpenAPI cepOpenApi() {
        return new OpenAPI()
                .info(new Info().title("Customer Engagement Platform API").version("v1")
                        .description("Audiences, journeys, events, notifications and customer profiles. "
                                + "Integration calls use X-API-Key; dashboard calls use a Bearer JWT from /api/v1/auth/login."))
                .components(new Components()
                        .addSecuritySchemes("apiKey", new SecurityScheme().type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER).name("X-API-Key"))
                        .addSecuritySchemes("bearer", new SecurityScheme().type(SecurityScheme.Type.HTTP)
                                .scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"))
                .addSecurityItem(new SecurityRequirement().addList("apiKey"));
    }
}
