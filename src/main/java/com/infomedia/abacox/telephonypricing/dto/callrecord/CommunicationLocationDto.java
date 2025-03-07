package com.infomedia.abacox.telephonypricing.dto.callrecord;

import com.infomedia.abacox.telephonypricing.dto.superclass.ActivableDto;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * DTO for {@link com.infomedia.abacox.telephonypricing.entity.CommunicationLocation}
 */
@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CommunicationLocationDto extends ActivableDto {
    private Long id;
    private String directory;
    private Long plantTypeId;
    private String serial;
    private Long indicatorId;
    private String pbxPrefix;
    private String address;
    private LocalDateTime captureDate;
    private Integer cdrCount;
    private String fileName;
    private Long bandGroupId;
    private Long headerId;
    private Integer withoutCaptures;
}