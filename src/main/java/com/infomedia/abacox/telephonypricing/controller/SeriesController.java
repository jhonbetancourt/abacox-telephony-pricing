package com.infomedia.abacox.telephonypricing.controller;


import com.infomedia.abacox.telephonypricing.component.modeltools.ModelConverter;
import com.infomedia.abacox.telephonypricing.dto.series.SeriesDto;
import com.infomedia.abacox.telephonypricing.dto.series.CreateSeries;
import com.infomedia.abacox.telephonypricing.dto.series.UpdateSeries;
import com.infomedia.abacox.telephonypricing.dto.superclass.ActivationDto;
import com.infomedia.abacox.telephonypricing.db.entity.Series;
import com.infomedia.abacox.telephonypricing.security.annotation.RequiresPermission;
import com.infomedia.abacox.telephonypricing.security.permissions.Permissions;
import com.infomedia.abacox.telephonypricing.service.SeriesService;
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
@Tag(name = "Series", description = "Series API")
@SecurityRequirements(value = {
        @SecurityRequirement(name = "JWT_Token"),
        @SecurityRequirement(name = "Tenant_Id")
})
@RequestMapping("/api/series")
public class SeriesController {

    private final SeriesService seriesService;
    private final ModelConverter modelConverter;

    @RequiresPermission(Permissions.NUMBERING_READ)
    @Operation(summary = "List series")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Slice<SeriesDto> find(@Parameter(hidden = true) @Filter Specification<Series> spec
            , @Parameter(hidden = true) Pageable pageable
            , @ParameterObject FilterRequest filterRequest
            , @ParameterObject PageableRequest pageableRequest) {
        return modelConverter.mapSlice(seriesService.find(spec, pageable), SeriesDto.class);
    }

    @RequiresPermission(Permissions.NUMBERING_CREATE)
    @Operation(summary = "Create a series")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public SeriesDto create(@Valid @RequestBody CreateSeries createSeries) {
        return modelConverter.map(seriesService.create(createSeries), SeriesDto.class);
    }

    @RequiresPermission(Permissions.NUMBERING_UPDATE)
    @Operation(summary = "Update a series")
    @PatchMapping(value = "{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public SeriesDto update(@PathVariable("id") Long id, @Valid @RequestBody UpdateSeries uDto) {
        return modelConverter.map(seriesService.update(id, uDto), SeriesDto.class);
    }

    @RequiresPermission(Permissions.NUMBERING_READ)
    @Operation(summary = "Get series by ID")
    @GetMapping(value = "{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public SeriesDto get(@PathVariable("id") Long id) {
        return modelConverter.map(seriesService.get(id), SeriesDto.class);
    }

    @RequiresPermission(Permissions.NUMBERING_UPDATE)
    @Operation(summary = "Change series activation status")
    @PatchMapping(value = "/status/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public SeriesDto activate(@PathVariable("id") Long id, @Valid @RequestBody ActivationDto activationDto) {
        return modelConverter.map(seriesService.changeActivation(id, activationDto.getActive()), SeriesDto.class);
    }

    @RequiresPermission(Permissions.NUMBERING_READ)
    @Operation(summary = "Export series to Excel")
    @GetMapping(value = "/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> exportExcel(@Parameter(hidden = true) @Filter Specification<Series> spec,
            @ParameterObject FilterRequest filterRequest,
            @ParameterObject ExportRequest exportRequest,
            @ParameterObject ExcelRequest excelRequest) {

        
        StreamingResponseBody body = out ->
            seriesService.exportExcelStreaming(spec, exportRequest.getSortOrder(), exportRequest.getMaxRows(), out, excelRequest.toExcelGeneratorBuilder());

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=series.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }
}