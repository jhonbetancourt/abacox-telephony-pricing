package com.infomedia.abacox.telephonypricing.config;

import com.infomedia.abacox.telephonypricing.constants.DateTimePattern;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.security.SecuritySchemes;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.DateSchema;
import io.swagger.v3.oas.models.media.DateTimeSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Configuration
@SecuritySchemes({
        @SecurityScheme(
                name = "JWT_Token",
                type = SecuritySchemeType.HTTP,
                bearerFormat = "JWT",
                scheme = "bearer"
        ),
        @SecurityScheme(
                name = "Username",
                type = SecuritySchemeType.APIKEY,
                paramName = "X-Username",
                in = SecuritySchemeIn.HEADER
        ),
        @SecurityScheme(
                name = "Tenant_Id",
                type = SecuritySchemeType.APIKEY,
                paramName = "X-Tenant-Id",
                in = SecuritySchemeIn.HEADER,
                description = "Tenant Identifier"
        ),
        @SecurityScheme(
                name = "Internal_Api_Key",
                type = SecuritySchemeType.APIKEY,
                paramName = "X-Internal-Api-Key",
                in = SecuritySchemeIn.HEADER,
                description = "API Key for internal service-to-service communication"
        )
})

public class SwaggerConfig {

    @Value("${info.build.name}")
    private String projectName;

    @Value("${info.build.version}")
    private String projectVersion;

    @Value("${info.build.description}")
    private String projectDescription;

    @Value("${springdoc.swagger-ui.server-url}")
    private String customServerUrl;


    @Bean
    public OpenApiCustomizer customizer() {
        LocalDateTime currentTime = LocalDateTime.now();
        final String dateTimeExample = currentTime
                .format(DateTimeFormatter.ofPattern(DateTimePattern.DATE_TIME));
        final String dateExample = currentTime.toLocalDate()
                .format(DateTimeFormatter.ofPattern(DateTimePattern.DATE));

        return openApi -> {
            openApi.info(new Info()
                    .title(projectName)
                    .version(projectVersion)
                    .description(projectDescription)
                    .contact(new Contact()
                            .name("Infomedia")
                            .url("https://www.infomediaservice.com/")));
            Map<String, Schema> schemas = openApi.getComponents().getSchemas();

            if (schemas != null) {
                schemas.forEach((s, schema) -> {

                    Map<String, Schema<?>> properties = schema.getProperties();
                    if (properties == null) {
                        properties = Map.of();
                    }

                    for (Map.Entry<String, Schema<?>> property : properties.entrySet()) {
                        if (property.getValue() instanceof DateTimeSchema) {
                            properties.replace(property.getKey(), new StringSchema()
                                    .example(dateTimeExample)
                                    .pattern("^\\d{2}-\\d{2}-\\d{4}'T'\\d{2}:\\d{2}:\\d{2}$")
                                    .description(property.getValue().getDescription()));
                        } else if (property.getValue() instanceof DateSchema) {
                            properties.replace(property.getKey(), new StringSchema()
                                    .example(dateExample)
                                    .pattern("^\\d{2}-\\d{2}-\\d{4}$")
                                    .description(property.getValue().getDescription()));
                        }
                    }
                });
            }

            if (customServerUrl != null && !customServerUrl.isBlank()) {
                openApi.servers(List.of(new Server().url(customServerUrl)));
            }
        };
    }
}
