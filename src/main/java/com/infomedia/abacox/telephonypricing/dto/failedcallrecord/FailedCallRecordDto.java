package com.infomedia.abacox.telephonypricing.dto.failedcallrecord;

import com.infomedia.abacox.telephonypricing.dto.commlocation.CommLocationDto;
import com.infomedia.abacox.telephonypricing.dto.superclass.AuditedDto;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * DTO for {@link com.infomedia.abacox.telephonypricing.db.entity.FailedCallRecord}
 */
@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class FailedCallRecordDto extends AuditedDto {
    private Long id;
    private String employeeExtension;
    private String errorType;
    private String errorMessage;
    private Long originalCallRecordId;
    private String processingStep;
    private Long fileInfoId;
    private Long commLocationId;
    private CommLocationDto commLocation;
    private String cdrString;
}