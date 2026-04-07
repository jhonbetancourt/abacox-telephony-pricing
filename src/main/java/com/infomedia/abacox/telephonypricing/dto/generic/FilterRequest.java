package com.infomedia.abacox.telephonypricing.dto.generic;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springdoc.core.annotations.ParameterObject;

/**
 * Parameter object for filtering
 */
@Data
@ParameterObject
public class FilterRequest {
    @Schema(description = "Filter expression")
    private String filter;
}
