package com.infomedia.abacox.telephonypricing.controller;

import com.infomedia.abacox.telephonypricing.component.export.excel.ExcelGeneratorBuilder;
import com.infomedia.abacox.telephonypricing.component.export.excel.ExportParamProcessor;
import com.infomedia.abacox.telephonypricing.component.modeltools.ModelConverter;
import com.infomedia.abacox.telephonypricing.dto.subdivision.SubdivisionDto;
import com.infomedia.abacox.telephonypricing.dto.subdivision.CreateSubdivision;
import com.infomedia.abacox.telephonypricing.dto.subdivision.UpdateSubdivision;
import com.infomedia.abacox.telephonypricing.dto.superclass.ActivationDto;
import com.infomedia.abacox.telephonypricing.db.entity.Subdivision;
import com.infomedia.abacox.telephonypricing.service.SubdivisionService;
import com.turkraft.springfilter.boot.Filter;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Base64;
import java.util.Map;
import java.util.Set;

@RequiredArgsConstructor
@RestController
@Tag(name = "Subdivision", description = "Subdivision API")
@SecurityRequirements({
        @SecurityRequirement(name = "JWT_Token"),
        @SecurityRequirement(name = "Username")
})
@RequestMapping("/api/subdivision")
public class SubdivisionController {

    private final SubdivisionService subdivisionService;
    private final ModelConverter modelConverter;
    private final ExportParamProcessor exportParamProcessor;

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Page<SubdivisionDto> find(@Parameter(hidden = true) @Filter Specification<Subdivision> spec
            , @Parameter(hidden = true) Pageable pageable
            , @RequestParam(required = false) String filter, @RequestParam(required = false) Integer page
            , @RequestParam(required = false) Integer size, @RequestParam(required = false) String sort) {
        return modelConverter.mapPage(subdivisionService.find(spec, pageable), SubdivisionDto.class);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public SubdivisionDto create(@Valid @RequestBody CreateSubdivision createSubdivision) {
        return modelConverter.map(subdivisionService.create(createSubdivision), SubdivisionDto.class);
    }

    @PatchMapping(value = "{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public SubdivisionDto update(@PathVariable("id") Long id, @Valid @RequestBody UpdateSubdivision uDto) {
        return modelConverter.map(subdivisionService.update(id, uDto), SubdivisionDto.class);
    }

    @GetMapping(value = "{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    private SubdivisionDto get(@PathVariable("id") Long id) {
        return modelConverter.map(subdivisionService.get(id), SubdivisionDto.class);
    }

    @PatchMapping(value = "/status/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public SubdivisionDto activate(@PathVariable("id") Long id, @Valid @RequestBody ActivationDto activationDto) {
        return modelConverter.map(subdivisionService.changeActivation(id, activationDto.getActive()), SubdivisionDto.class);
    }

    @GetMapping(value = "/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<Resource> exportExcel(@Parameter(hidden = true) @Filter Specification<Subdivision> spec
            , @Parameter(hidden = true) Pageable pageable
            , @RequestParam(required = false) String filter, @RequestParam(required = false) Integer page
            , @RequestParam(required = false) Integer size, @RequestParam(required = false) String sort
            , @RequestParam(required = false) String alternativeHeaders
            , @RequestParam(required = false) String excludeColumns
            , @RequestParam(required = false) String includeColumns
            , @RequestParam(required = false) String valueReplacements) {

        ExcelGeneratorBuilder excelGeneratorBuilder = exportParamProcessor.base64ParamsToExcelGeneratorBuilder(
                alternativeHeaders, excludeColumns, includeColumns, valueReplacements);


        ByteArrayResource resource = subdivisionService.exportExcel(spec, pageable, excelGeneratorBuilder);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=subdivisions.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(resource);
    }
}