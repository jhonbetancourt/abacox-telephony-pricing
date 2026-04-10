package com.infomedia.abacox.telephonypricing.controller;

import com.infomedia.abacox.telephonypricing.component.modeltools.ModelConverter;
import com.infomedia.abacox.telephonypricing.dto.extensionrange.ExtensionRangeDto;
import com.infomedia.abacox.telephonypricing.dto.extensionrange.CreateExtensionRange;
import com.infomedia.abacox.telephonypricing.dto.extensionrange.UpdateExtensionRange;
import com.infomedia.abacox.telephonypricing.dto.superclass.ActivationDto;
import com.infomedia.abacox.telephonypricing.db.entity.ExtensionRange;
import com.infomedia.abacox.telephonypricing.security.annotation.RequiresPermission;
import com.infomedia.abacox.telephonypricing.service.ExtensionRangeService;
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
@Tag(name = "Extension Range", description = "Extension Range API")
@SecurityRequirements(value = {
        @SecurityRequirement(name = "JWT_Token"),
        @SecurityRequirement(name = "Username"),
        @SecurityRequirement(name = "Tenant_Id")
})
@RequestMapping("/api/extensionRange")
public class ExtensionRangeController {

    private final ExtensionRangeService extensionRangeService;
    private final ModelConverter modelConverter;

    @RequiresPermission("telephony-config:read")
    @Operation(summary = "List extension ranges")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Slice<ExtensionRangeDto> find(@Parameter(hidden = true) @Filter Specification<ExtensionRange> spec,
            @Parameter(hidden = true) Pageable pageable
            , @ParameterObject FilterRequest filterRequest
            , @ParameterObject PageableRequest pageableRequest) {
        return modelConverter.mapSlice(extensionRangeService.find(spec, pageable), ExtensionRangeDto.class);
    }

    @RequiresPermission("telephony-config:create")
    @Operation(summary = "Create an extension range")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ExtensionRangeDto create(@Valid @RequestBody CreateExtensionRange createExtensionRange) {
        return modelConverter.map(extensionRangeService.create(createExtensionRange), ExtensionRangeDto.class);
    }

    @RequiresPermission("telephony-config:update")
    @Operation(summary = "Update an extension range")
    @PatchMapping(value = "{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ExtensionRangeDto update(@PathVariable("id") Long id,
            @Valid @RequestBody UpdateExtensionRange updateExtensionRange) {
        return modelConverter.map(extensionRangeService.update(id, updateExtensionRange), ExtensionRangeDto.class);
    }

    @RequiresPermission("telephony-config:update")
    @Operation(summary = "Change extension range activation status")
    @PatchMapping(value = "/status/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ExtensionRangeDto activate(@PathVariable("id") Long id, @Valid @RequestBody ActivationDto activationDto) {
        return modelConverter.map(extensionRangeService.changeActivation(id, activationDto.getActive()),
                ExtensionRangeDto.class);
    }

    @RequiresPermission("telephony-config:update")
    @Operation(summary = "Retire an extension range")
    @PatchMapping(value = "/retire/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> retire(@PathVariable("id") Long id) {
        extensionRangeService.retire(id);
        return ResponseEntity.noContent().build();
    }

    @RequiresPermission("telephony-config:read")
    @Operation(summary = "Get extension range by ID")
    @GetMapping(value = "{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ExtensionRangeDto get(@PathVariable("id") Long id) {
        return modelConverter.map(extensionRangeService.get(id), ExtensionRangeDto.class);
    }

    @RequiresPermission("telephony-config:read")
    @Operation(summary = "Export extension ranges to Excel")
    @GetMapping(value = "/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> exportExcel(@Parameter(hidden = true) @Filter Specification<ExtensionRange> spec,
            @ParameterObject FilterRequest filterRequest,
            @ParameterObject ExportRequest exportRequest,
            @ParameterObject ExcelRequest excelRequest) {

        StreamingResponseBody body = out ->
            extensionRangeService.exportExcelStreaming(spec, exportRequest.getSortOrder(), exportRequest.getMaxRows(), out, excelRequest.toExcelGeneratorBuilder());

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=extension_ranges.xlsx")
                .contentType(
                        MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }

}