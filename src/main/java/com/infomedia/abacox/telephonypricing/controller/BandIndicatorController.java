package com.infomedia.abacox.telephonypricing.controller;

import com.infomedia.abacox.telephonypricing.component.modeltools.ModelConverter;
import com.infomedia.abacox.telephonypricing.dto.bandindicator.BandIndicatorDto;
import com.infomedia.abacox.telephonypricing.dto.bandindicator.CreateBandIndicator;
import com.infomedia.abacox.telephonypricing.dto.bandindicator.UpdateBandIndicator;
import com.infomedia.abacox.telephonypricing.db.entity.BandIndicator;
import com.infomedia.abacox.telephonypricing.security.annotation.RequiresPermission;
import com.infomedia.abacox.telephonypricing.security.permissions.Permissions;
import com.infomedia.abacox.telephonypricing.service.BandIndicatorService;
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
@Tag(name = "BandIndicator", description = "BandIndicator API")
@SecurityRequirements(value = {
        @SecurityRequirement(name = "JWT_Token"),
        @SecurityRequirement(name = "Tenant_Id")
})
@RequestMapping("/api/bandIndicator")
public class BandIndicatorController {

    private final BandIndicatorService bandIndicatorService;
    private final ModelConverter modelConverter;

    @RequiresPermission(Permissions.NUMBERING_READ)
    @Operation(summary = "List band indicators")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Slice<BandIndicatorDto> find(@Parameter(hidden = true) @Filter Specification<BandIndicator> spec
            , @Parameter(hidden = true) Pageable pageable
            , @ParameterObject FilterRequest filterRequest
            , @ParameterObject PageableRequest pageableRequest) {
        return modelConverter.mapSlice(bandIndicatorService.find(spec, pageable), BandIndicatorDto.class);
    }

    @RequiresPermission(Permissions.NUMBERING_CREATE)
    @Operation(summary = "Create a band indicator")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public BandIndicatorDto create(@Valid @RequestBody CreateBandIndicator createBandIndicator) {
        return modelConverter.map(bandIndicatorService.create(createBandIndicator), BandIndicatorDto.class);
    }

    @RequiresPermission(Permissions.NUMBERING_UPDATE)
    @Operation(summary = "Update a band indicator")
    @PatchMapping(value = "{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public BandIndicatorDto update(@PathVariable("id") Long id, @Valid @RequestBody UpdateBandIndicator uDto) {
        return modelConverter.map(bandIndicatorService.update(id, uDto), BandIndicatorDto.class);
    }

    @RequiresPermission(Permissions.NUMBERING_READ)
    @Operation(summary = "Get band indicator by ID")
    @GetMapping(value = "{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public BandIndicatorDto get(@PathVariable("id") Long id) {
        return modelConverter.map(bandIndicatorService.get(id), BandIndicatorDto.class);
    }

    @RequiresPermission(Permissions.NUMBERING_READ)
    @Operation(summary = "Export band indicators to Excel")
    @GetMapping(value = "/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> exportExcel(@Parameter(hidden = true) @Filter Specification<BandIndicator> spec,
            @ParameterObject FilterRequest filterRequest,
            @ParameterObject ExportRequest exportRequest,
            @ParameterObject ExcelRequest excelRequest) {

        
        StreamingResponseBody body = out ->
            bandIndicatorService.exportExcelStreaming(spec, exportRequest.getSortOrder(), exportRequest.getMaxRows(), out, excelRequest.toExcelGeneratorBuilder());

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=bands_indicators.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }
}