package com.infomedia.abacox.telephonypricing.dto.commlocation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * DTO for creating {@link com.infomedia.abacox.telephonypricing.entity.CommunicationLocation}
 */
@EqualsAndHashCode(callSuper = false)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CreateCommLocation {
    @NotBlank
    @Size(max = 80)
    private String directory;
    
    private Long plantTypeId;
    
    @NotBlank
    @Size(max = 20)
    private String serial;
    
    private Long indicatorId;
    
    @NotBlank
    @Size(max = 32)
    private String pbxPrefix;
    
    @NotBlank
    @Size(max = 100)
    private String address;
    
    private LocalDateTime captureDate;
    
    @NotNull
    private Integer cdrCount = 0;
    
    @NotBlank
    @Size(max = 80)
    private String fileName;
    
    private Long bandGroupId;
    
    private Long headerId;
    
    @NotNull
    private Integer withoutCaptures = 0;
}