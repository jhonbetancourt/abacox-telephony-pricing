package com.infomedia.abacox.telephonypricing.controller;


import com.infomedia.abacox.telephonypricing.component.modeltools.ModelConverter;
import com.infomedia.abacox.telephonypricing.dto.specialratevalue.SpecialRateValueDto;
import com.infomedia.abacox.telephonypricing.dto.specialratevalue.CreateSpecialRateValue;
import com.infomedia.abacox.telephonypricing.dto.specialratevalue.UpdateSpecialRateValue;
import com.infomedia.abacox.telephonypricing.dto.superclass.ActivationDto;
import com.infomedia.abacox.telephonypricing.db.entity.SpecialRateValue;
import com.infomedia.abacox.telephonypricing.security.annotation.RequiresPermission;
import com.infomedia.abacox.telephonypricing.security.permissions.Permissions;
import com.infomedia.abacox.telephonypricing.service.SpecialRateValueService;
import com.turkraft.springfilter.boot.Filter;
import io.swagger.v3.oas.annotations.Operation;
import com.infomedia.abacox.telephonypricing.dto.generic.ExcelRequest;
import com.infomedia.abacox.telephonypricing.dto.generic.ExportRequest;
import com.infomedia.abacox.telephonypricing.dto.generic.FilterRequest;
import com.infomedia.abacox.telephonypricing.dto.generic.PageableRequest;
import io.swagger.v3.oas.annotations.Parameter;
import org.springdoc.core.annotations.ParameterObject;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RequiredArgsConstructor
@RestController
@Tag(name = "SpecialRateValue", description = "Special Rate Value API")
@SecurityRequirements(value = {
        @SecurityRequirement(name = "JWT_Token"),
        @SecurityRequirement(name = "Tenant_Id")
})
@RequestMapping("/api/specialRateValue")
public class SpecialRateValueController {

    private final SpecialRateValueService specialRateValueService;
    private final ModelConverter modelConverter;

    @RequiresPermission(Permissions.PRICING_READ)
    @Operation(summary = "List special rate values")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Slice<SpecialRateValueDto> find(@Parameter(hidden = true) @Filter Specification<SpecialRateValue> spec
            , @Parameter(hidden = true) Pageable pageable
            , @ParameterObject FilterRequest filterRequest
            , @ParameterObject PageableRequest pageableRequest) {
        return modelConverter.mapSlice(specialRateValueService.find(spec, pageable), SpecialRateValueDto.class);
    }

    @RequiresPermission(Permissions.PRICING_CREATE)
    @Operation(summary = "Create a special rate value")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public SpecialRateValueDto create(@Valid @RequestBody CreateSpecialRateValue createSpecialRateValue) {
        return modelConverter.map(specialRateValueService.create(createSpecialRateValue), SpecialRateValueDto.class);
    }

    @RequiresPermission(Permissions.PRICING_UPDATE)
    @Operation(summary = "Update a special rate value")
    @PatchMapping(value = "{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public SpecialRateValueDto update(@PathVariable("id") Long id, @Valid @RequestBody UpdateSpecialRateValue uDto) {
        return modelConverter.map(specialRateValueService.update(id, uDto), SpecialRateValueDto.class);
    }

    @RequiresPermission(Permissions.PRICING_UPDATE)
    @Operation(summary = "Change special rate value activation status")
    @PatchMapping(value = "/status/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public SpecialRateValueDto activate(@PathVariable("id") Long id, @Valid @RequestBody ActivationDto activationDto) {
        return modelConverter.map(specialRateValueService.changeActivation(id, activationDto.getActive()), SpecialRateValueDto.class);
    }

    @RequiresPermission(Permissions.PRICING_READ)
    @Operation(summary = "Get special rate value by ID")
    @GetMapping(value = "{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public SpecialRateValueDto get(@PathVariable("id") Long id) {
        return modelConverter.map(specialRateValueService.get(id), SpecialRateValueDto.class);
    }

    @RequiresPermission(Permissions.PRICING_READ)
    @Operation(summary = "Export special rate values to Excel")
    @GetMapping(value = "/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> exportExcel(@Parameter(hidden = true) @Filter Specification<SpecialRateValue> spec,
            @ParameterObject FilterRequest filterRequest,
            @ParameterObject ExportRequest exportRequest,
            @ParameterObject ExcelRequest excelRequest) {

        
        StreamingResponseBody body = out ->
            specialRateValueService.exportExcelStreaming(spec, exportRequest.getSortOrder(), exportRequest.getMaxRows(), out, excelRequest.toExcelGeneratorBuilder());

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=special_rate_values.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }
}