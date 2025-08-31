package com.infomedia.abacox.telephonypricing.dto.configuration;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.infomedia.abacox.telephonypricing.constants.DateTimePattern;
import lombok.*;

import java.time.LocalDate;

/**
 * DTO for reading the complete set of CDR processing configurations.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ConfigurationDto {
    private Integer serviceDateHourOffset;
    private Boolean specialValueTariffing;
    private Integer minCallDurationForTariffing;
    private Integer maxCallDurationMinutes;
    @JsonFormat(pattern = DateTimePattern.DATE)
    private LocalDate minAllowedCaptureDate;
    private Integer maxAllowedCaptureDateDaysInFuture;
    private Boolean createEmployeesAutomaticallyFromRange;
    private Long defaultUnresolvedInternalCallTypeId;
    private Long defaultInternalCallTypeId;
    private Boolean extensionsGlobal;
    private Boolean authCodesGlobal;
    private String assumedText;
    private String originText;
    private String prefixText;
    private String employeeNamePrefixFromRange;
    private String noPartitionPlaceholder;
    private String cdrUploadApiKey;
}

