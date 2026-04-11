package com.infomedia.abacox.telephonypricing.controller;


import com.infomedia.abacox.telephonypricing.component.modeltools.ModelConverter;
import com.infomedia.abacox.telephonypricing.dto.specialservice.SpecialServiceDto;
import com.infomedia.abacox.telephonypricing.dto.specialservice.CreateSpecialService;
import com.infomedia.abacox.telephonypricing.dto.specialservice.UpdateSpecialService;
import com.infomedia.abacox.telephonypricing.dto.superclass.ActivationDto;
import com.infomedia.abacox.telephonypricing.db.entity.SpecialService;
import com.infomedia.abacox.telephonypricing.security.annotation.RequiresPermission;
import com.infomedia.abacox.telephonypricing.security.permissions.Permissions;
import com.infomedia.abacox.telephonypricing.service.SpecialServiceService;
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
@Tag(name = "Special Service", description = "Special Service API")
@SecurityRequirements(value = {
        @SecurityRequirement(name = "JWT_Token"),
        @SecurityRequirement(name = "Tenant_Id")
})
@RequestMapping("/api/specialService")
public class SpecialServiceController {

    private final SpecialServiceService specialServiceService;
    private final ModelConverter modelConverter;

    @RequiresPermission(Permissions.PRICING_READ)
    @Operation(summary = "List special services")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Slice<SpecialServiceDto> find(@Parameter(hidden = true) @Filter Specification<SpecialService> spec
            , @Parameter(hidden = true) Pageable pageable
            , @ParameterObject FilterRequest filterRequest
            , @ParameterObject PageableRequest pageableRequest) {
        return modelConverter.mapSlice(specialServiceService.find(spec, pageable), SpecialServiceDto.class);
    }

    @RequiresPermission(Permissions.PRICING_CREATE)
    @Operation(summary = "Create a special service")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public SpecialServiceDto create(@Valid @RequestBody CreateSpecialService createSpecialService) {
        return modelConverter.map(specialServiceService.create(createSpecialService), SpecialServiceDto.class);
    }

    @RequiresPermission(Permissions.PRICING_UPDATE)
    @Operation(summary = "Update a special service")
    @PatchMapping(value = "{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public SpecialServiceDto update(@PathVariable("id") Long id, @Valid @RequestBody UpdateSpecialService updateSpecialService) {
        return modelConverter.map(specialServiceService.update(id, updateSpecialService), SpecialServiceDto.class);
    }

    @RequiresPermission(Permissions.PRICING_UPDATE)
    @Operation(summary = "Change special service activation status")
    @PatchMapping(value = "/status/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public SpecialServiceDto activate(@PathVariable("id") Long id, @Valid @RequestBody ActivationDto activationDto) {
        return modelConverter.map(specialServiceService.changeActivation(id, activationDto.getActive()), SpecialServiceDto.class);
    }

    @RequiresPermission(Permissions.PRICING_READ)
    @Operation(summary = "Get special service by ID")
    @GetMapping(value = "{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public SpecialServiceDto get(@PathVariable("id") Long id) {
        return modelConverter.map(specialServiceService.get(id), SpecialServiceDto.class);
    }

    @RequiresPermission(Permissions.PRICING_READ)
    @Operation(summary = "Export special services to Excel")
    @GetMapping(value = "/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> exportExcel(@Parameter(hidden = true) @Filter Specification<SpecialService> spec,
            @ParameterObject FilterRequest filterRequest,
            @ParameterObject ExportRequest exportRequest,
            @ParameterObject ExcelRequest excelRequest) {

        
        StreamingResponseBody body = out ->
            specialServiceService.exportExcelStreaming(spec, exportRequest.getSortOrder(), exportRequest.getMaxRows(), out, excelRequest.toExcelGeneratorBuilder());

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=special_services.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }

}