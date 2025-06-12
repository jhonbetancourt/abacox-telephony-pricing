package com.infomedia.abacox.telephonypricing.dto.configuration;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.infomedia.abacox.telephonypricing.constants.DateTimePattern;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.openapitools.jackson.nullable.JsonNullable;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * DTO for updating CDR processing configurations.
 * Uses JsonNullable to distinguish between a field that is not provided
 * and a field that is explicitly set to null.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class UpdateConfigurationDto {

    @NotNull
    private JsonNullable<String> serviceDateHourOffset = JsonNullable.undefined();

    @NotNull
    @Schema(description = "Enable or disable special value tariffing")
    private JsonNullable<Boolean> specialValueTariffingEnabled = JsonNullable.undefined();

    @NotNull
    @Min(0)
    @Schema(description = "Minimum call duration in seconds to be considered for tariffing")
    private JsonNullable<Integer> minCallDurationForTariffing = JsonNullable.undefined();

    @NotNull
    @Min(0)
    @Schema(description = "Maximum allowed call duration in seconds. Calls exceeding this duration will be rejected.")
    private JsonNullable<Integer> maxCallDurationMinutes = JsonNullable.undefined();

    @NotNull
    @JsonFormat(pattern = DateTimePattern.DATE)
    @Schema(description = "The earliest date allowed for a call's capture date, in 'YYYY-MM-DD' format. Calls before this date will be rejected.")
    private JsonNullable<LocalDate> minAllowedCaptureDate = JsonNullable.undefined();

    @NotNull
    @Min(0)
    @Schema(description = "The maximum number of days in the future a call's capture date can be. Calls beyond this will be rejected.")
    private JsonNullable<Integer> maxAllowedCaptureDateDaysInFuture = JsonNullable.undefined();

    @NotNull
    @Schema(description = "Create employees automatically if they are found in a defined extension range")
    private JsonNullable<Boolean> createEmployeesAutomaticallyFromRange = JsonNullable.undefined();

    @NotNull
    @Schema(description = "Default Telephony Type ID for unresolved internal calls")
    private JsonNullable<Long> defaultUnresolvedInternalCallTypeId = JsonNullable.undefined();

    @NotNull
    @Schema(description = "Default Telephony Type ID for internal calls")
    private JsonNullable<Long> defaultInternalCallTypeId = JsonNullable.undefined();

    @NotNull
    @Schema(description = "Whether extensions are considered global across all communication locations")
    private JsonNullable<Boolean> extensionsGlobal = JsonNullable.undefined();

    @NotNull
    @Schema(description = "Whether auth codes are considered global across all communication locations")
    private JsonNullable<Boolean> authCodesGlobal = JsonNullable.undefined();

    @NotNull
    @Schema(description = "Text used to identify assumed calls.")
    private JsonNullable<String> assumedText = JsonNullable.undefined();

    @NotNull
    @Schema(description = "Text used to identify the origin of calls.")
    private JsonNullable<String> originText = JsonNullable.undefined();

    @NotNull
    @Schema(description = "Text used to identify prefixes.")
    private JsonNullable<String> prefixText = JsonNullable.undefined();

    @NotNull
    @Schema(description = "Prefix for the name of employees created automatically from an extension range.")
    private JsonNullable<String> employeeNamePrefixFromRange = JsonNullable.undefined();

    @NotNull
    @Schema(description = "Placeholder text for when no partition is assigned.")
    private JsonNullable<String> noPartitionPlaceholder = JsonNullable.undefined();
}