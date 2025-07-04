package com.infomedia.abacox.telephonypricing.dto.generic;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springdoc.core.annotations.ParameterObject;

/**
 * Parameter object for pagination
 */
@Data
@ParameterObject
public class PageableRequest {

    @Schema(description = "Page number (0-based)")
    private Integer page;

    @Schema(description = "Page size")
    private Integer size;

    @Schema(description = "Sort expression (format: property,direction|property,direction)")
    private String sort;
}