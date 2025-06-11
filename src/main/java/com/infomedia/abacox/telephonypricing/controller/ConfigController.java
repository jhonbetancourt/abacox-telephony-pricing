package com.infomedia.abacox.telephonypricing.controller;

import com.infomedia.abacox.telephonypricing.component.modeltools.ModelConverter;
import com.infomedia.abacox.telephonypricing.dto.configuration.ConfigurationDto;
import com.infomedia.abacox.telephonypricing.service.ConfigService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RequiredArgsConstructor
@RestController
@Tag(name = "Configuration", description = "Configuration controller")
@SecurityRequirements({
        @SecurityRequirement(name = "JWT_Token"),
        @SecurityRequirement(name = "Username")
})
@RequestMapping("/api/configuration")
public class ConfigController {

    private final ConfigService configService;
    private final ModelConverter modelConverter;

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ConfigurationDto getConfiguration() {
        Map<String, Object> configMap = configService.getPublicConfiguration();
        return modelConverter.fromMap(configMap, ConfigurationDto.class);
    }

    /*@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ConfigurationDto updateConfiguration(@RequestBody UpdateConfigurationDto newConfig) {
        Map<String, Object> updatedConfig = new HashMap<>();
        newConfig.getTargetDatabaseTimezone().ifPresent(value ->
                updatedConfig.put(ConfigKey.TARGET_DATABASE_TIMEZONE.name(), value));
        newConfig.getSpecialValueTariffingEnabled().ifPresent(value ->
                updatedConfig.put(ConfigKey.SPECIAL_VALUE_TARIFFING_ENABLED.name(), value));
        newConfig.getMinCallDurationForTariffing().ifPresent(value ->
                updatedConfig.put(ConfigKey.MIN_CALL_DURATION_FOR_TARIFFING.name(), value));
        newConfig.getMaxCallDurationMinutes().ifPresent(value ->
                updatedConfig.put(ConfigKey.MAX_CALL_DURATION_MINUTES.name(), value));
        newConfig.getMinAllowedCaptureDate().ifPresent(value ->
                updatedConfig.put(ConfigKey.MIN_ALLOWED_CAPTURE_DATE.name(), value.toString()));
        newConfig.getMaxAllowedCaptureDateDaysInFuture().ifPresent(value ->
                updatedConfig.put(ConfigKey.MAX_ALLOWED_CAPTURE_DATE_DAYS_IN_FUTURE.name(), value));
        newConfig.getCreateEmployeesAutomaticallyFromRange().ifPresent(value ->
                updatedConfig.put(ConfigKey.CREATE_EMPLOYEES_AUTOMATICALLY_FROM_RANGE.name(), value));
        newConfig.getDefaultTelephonyTypeForUnresolvedInternalCalls().ifPresent(value ->
                updatedConfig.put(ConfigKey.DEFAULT_TELEPHONY_TYPE_FOR_UNRESOLVED_INTERNAL.name(), value));
        newConfig.getDefaultInternalCallTypeId().ifPresent(value ->
                updatedConfig.put(ConfigKey.DEFAULT_INTERNAL_CALL_TYPE_ID.name(), value));
        newConfig.getAreExtensionsGlobal().ifPresent(value ->
                updatedConfig.put(ConfigKey.EXTENSIONS_GLOBAL.name(), value));
        newConfig.getAreAuthCodesGlobal().ifPresent(value ->
                updatedConfig.put(ConfigKey.AUTH_CODES_GLOBAL.name(), value));
        newConfig.getAssumedText().ifPresent(value ->
                updatedConfig.put(ConfigKey.ASSUMED_TEXT.name(), value));
        newConfig.getOriginText().ifPresent(value ->
                updatedConfig.put(ConfigKey.ORIGIN_TEXT.name(), value));
        newConfig.getPrefixText().ifPresent(value ->
                updatedConfig.put(ConfigKey.PREFIX_TEXT.name(), value));
        newConfig.getEmployeeNamePrefixFromRange().ifPresent(value ->
                updatedConfig.put(ConfigKey.EMPLOYEE_NAME_PREFIX_FROM_RANGE.name(), value));
        updatedConfig.put(ConfigKey.NO_PARTITION_PLACEHOLDER.name(), ConfigKey.NO_PARTITION_PLACEHOLDER.getDefaultValue());
        configService.updatePublicConfiguration(updatedConfig);
        return getConfiguration();
    }*/
}
