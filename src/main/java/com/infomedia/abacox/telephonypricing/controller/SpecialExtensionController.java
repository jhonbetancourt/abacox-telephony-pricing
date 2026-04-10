package com.infomedia.abacox.telephonypricing.controller;

import com.infomedia.abacox.telephonypricing.component.modeltools.ModelConverter;
import com.infomedia.abacox.telephonypricing.db.entity.SpecialExtension;
import com.infomedia.abacox.telephonypricing.dto.generic.ExcelRequest;
import com.infomedia.abacox.telephonypricing.dto.generic.ExportRequest;
import com.infomedia.abacox.telephonypricing.dto.generic.FilterRequest;
import com.infomedia.abacox.telephonypricing.dto.generic.PageableRequest;
import com.infomedia.abacox.telephonypricing.dto.specialextension.CreateSpecialExtension;
import com.infomedia.abacox.telephonypricing.dto.specialextension.SpecialExtensionDto;
import com.infomedia.abacox.telephonypricing.dto.specialextension.UpdateSpecialExtension;
import com.infomedia.abacox.telephonypricing.dto.superclass.ActivationDto;
import com.infomedia.abacox.telephonypricing.security.annotation.RequiresPermission;
import com.infomedia.abacox.telephonypricing.service.SpecialExtensionService;
import com.turkraft.springfilter.boot.Filter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
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
@Tag(name = "Special Extension", description = "Special Extension API")
@SecurityRequirements(value = {
        @SecurityRequirement(name = "JWT_Token"),
        @SecurityRequirement(name = "Username"),
        @SecurityRequirement(name = "Tenant_Id")
})
@RequestMapping("/api/specialExtension")
public class SpecialExtensionController {

    private final SpecialExtensionService specialExtensionService;
    private final ModelConverter modelConverter;

    @RequiresPermission("telephony-config:read")
    @Operation(summary = "List special extensions")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Slice<SpecialExtensionDto> find(@Parameter(hidden = true) @Filter Specification<SpecialExtension> spec,
                                          @Parameter(hidden = true) Pageable pageable,
                                          @ParameterObject FilterRequest filterRequest
            , @ParameterObject PageableRequest pageableRequest) {
        return modelConverter.mapSlice(specialExtensionService.find(spec, pageable), SpecialExtensionDto.class);
    }

    @RequiresPermission("telephony-config:create")
    @Operation(summary = "Create a special extension")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public SpecialExtensionDto create(@Valid @RequestBody CreateSpecialExtension createDto) {
        return modelConverter.map(specialExtensionService.create(createDto), SpecialExtensionDto.class);
    }

    @RequiresPermission("telephony-config:update")
    @Operation(summary = "Update a special extension")
    @PatchMapping(value = "{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public SpecialExtensionDto update(@PathVariable("id") Long id, @Valid @RequestBody UpdateSpecialExtension uDto) {
        return modelConverter.map(specialExtensionService.update(id, uDto), SpecialExtensionDto.class);
    }

    @RequiresPermission("telephony-config:read")
    @Operation(summary = "Get special extension by ID")
    @GetMapping(value = "{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public SpecialExtensionDto get(@PathVariable("id") Long id) {
        return modelConverter.map(specialExtensionService.get(id), SpecialExtensionDto.class);
    }

    @RequiresPermission("telephony-config:update")
    @Operation(summary = "Change special extension activation status")
    @PatchMapping(value = "/status/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public SpecialExtensionDto activate(@PathVariable("id") Long id, @Valid @RequestBody ActivationDto activationDto) {
        return modelConverter.map(specialExtensionService.changeActivation(id, activationDto.getActive()), SpecialExtensionDto.class);
    }

    @RequiresPermission("telephony-config:read")
    @Operation(summary = "Export special extensions to Excel")
    @GetMapping(value = "/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> exportExcel(@Parameter(hidden = true) @Filter Specification<SpecialExtension> spec,
                                                @ParameterObject FilterRequest filterRequest,
                                                @ParameterObject ExportRequest exportRequest,
                                                @ParameterObject ExcelRequest excelRequest) {

        StreamingResponseBody body = out ->
            specialExtensionService.exportExcelStreaming(spec, exportRequest.getSortOrder(), exportRequest.getMaxRows(), out, excelRequest.toExcelGeneratorBuilder());

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=special-extensions.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }
}