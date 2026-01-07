package com.infomedia.abacox.telephonypricing.controller;

import com.infomedia.abacox.telephonypricing.component.configmanager.ConfigGroup;
import com.infomedia.abacox.telephonypricing.component.configmanager.ConfigService;
import com.infomedia.abacox.telephonypricing.component.modeltools.ModelConverter;
import com.infomedia.abacox.telephonypricing.dto.configuration.ConfigurationDto;
import com.infomedia.abacox.telephonypricing.dto.configuration.UpdateConfigurationDto;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RequiredArgsConstructor
@RestController
@Tag(name = "Configuration", description = "Configuration controller")
@SecurityRequirements(value = {
        @SecurityRequirement(name = "JWT_Token"),
        @SecurityRequirement(name = "Username"),
        @SecurityRequirement(name = "Tenant_Id")
})
@RequestMapping("/api/configuration")
public class ConfigController {

    private final ConfigService configService;
    private final ModelConverter modelConverter;

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ConfigurationDto getConfiguration() {
        Map<String, Object> configMap = configService.getConfigurationMap(ConfigGroup.CDR);
        return modelConverter.fromMap(configMap, ConfigurationDto.class);
    }

    @PatchMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ConfigurationDto updateConfiguration(@Valid @RequestBody UpdateConfigurationDto newConfig) {
        Map<String, Object> newConfigMap = modelConverter.toMap(newConfig);
        configService.updateConfiguration(ConfigGroup.CDR, newConfigMap);
        return getConfiguration();
    }
}
