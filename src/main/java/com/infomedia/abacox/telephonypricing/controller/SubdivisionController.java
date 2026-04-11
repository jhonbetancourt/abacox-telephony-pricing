package com.infomedia.abacox.telephonypricing.controller;


import com.infomedia.abacox.telephonypricing.component.modeltools.ModelConverter;
import com.infomedia.abacox.telephonypricing.dto.subdivision.SubdivisionDto;
import com.infomedia.abacox.telephonypricing.dto.subdivision.CreateSubdivision;
import com.infomedia.abacox.telephonypricing.dto.subdivision.UpdateSubdivision;
import com.infomedia.abacox.telephonypricing.dto.superclass.ActivationDto;
import com.infomedia.abacox.telephonypricing.db.entity.Subdivision;
import com.infomedia.abacox.telephonypricing.security.annotation.RequiresPermission;
import com.infomedia.abacox.telephonypricing.security.permissions.Permissions;
import com.infomedia.abacox.telephonypricing.service.SubdivisionService;
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
@Tag(name = "Subdivision", description = "Subdivision API")
@SecurityRequirements(value = {
        @SecurityRequirement(name = "JWT_Token"),
        @SecurityRequirement(name = "Username"),
        @SecurityRequirement(name = "Tenant_Id")
})
@RequestMapping("/api/subdivision")
public class SubdivisionController {

    private final SubdivisionService subdivisionService;
    private final ModelConverter modelConverter;

    @RequiresPermission(Permissions.SUBDIVISION_READ)
    @Operation(summary = "List subdivisions")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Slice<SubdivisionDto> find(@Parameter(hidden = true) @Filter Specification<Subdivision> spec
            , @Parameter(hidden = true) Pageable pageable
            , @ParameterObject FilterRequest filterRequest
            , @ParameterObject PageableRequest pageableRequest) {
        return modelConverter.mapSlice(subdivisionService.find(spec, pageable), SubdivisionDto.class);
    }

    @RequiresPermission(Permissions.SUBDIVISION_CREATE)
    @Operation(summary = "Create a subdivision")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public SubdivisionDto create(@Valid @RequestBody CreateSubdivision createSubdivision) {
        return modelConverter.map(subdivisionService.create(createSubdivision), SubdivisionDto.class);
    }

    @RequiresPermission(Permissions.SUBDIVISION_UPDATE)
    @Operation(summary = "Update a subdivision")
    @PatchMapping(value = "{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public SubdivisionDto update(@PathVariable("id") Long id, @Valid @RequestBody UpdateSubdivision uDto) {
        return modelConverter.map(subdivisionService.update(id, uDto), SubdivisionDto.class);
    }

    @RequiresPermission(Permissions.SUBDIVISION_READ)
    @Operation(summary = "Get subdivision by ID")
    @GetMapping(value = "{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    private SubdivisionDto get(@PathVariable("id") Long id) {
        return modelConverter.map(subdivisionService.get(id), SubdivisionDto.class);
    }

    @RequiresPermission(Permissions.SUBDIVISION_UPDATE)
    @Operation(summary = "Change subdivision activation status")
    @PatchMapping(value = "/status/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public SubdivisionDto activate(@PathVariable("id") Long id, @Valid @RequestBody ActivationDto activationDto) {
        return modelConverter.map(subdivisionService.changeActivation(id, activationDto.getActive()), SubdivisionDto.class);
    }

    @RequiresPermission(Permissions.SUBDIVISION_READ)
    @Operation(summary = "Export subdivisions to Excel")
    @GetMapping(value = "/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> exportExcel(@Parameter(hidden = true) @Filter Specification<Subdivision> spec,
            @ParameterObject FilterRequest filterRequest,
            @ParameterObject ExportRequest exportRequest,
            @ParameterObject ExcelRequest excelRequest) {

        
        StreamingResponseBody body = out ->
            subdivisionService.exportExcelStreaming(spec, exportRequest.getSortOrder(), exportRequest.getMaxRows(), out, excelRequest.toExcelGeneratorBuilder());

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=subdivisions.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }
}