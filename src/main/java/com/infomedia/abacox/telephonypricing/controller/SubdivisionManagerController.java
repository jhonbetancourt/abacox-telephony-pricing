package com.infomedia.abacox.telephonypricing.controller;

import com.infomedia.abacox.telephonypricing.component.export.excel.ExcelGeneratorBuilder;
import com.infomedia.abacox.telephonypricing.component.export.excel.ExportParamProcessor;
import com.infomedia.abacox.telephonypricing.component.modeltools.ModelConverter;
import com.infomedia.abacox.telephonypricing.dto.subdivisionmanager.SubdivisionManagerDto;
import com.infomedia.abacox.telephonypricing.dto.subdivisionmanager.CreateSubdivisionManager;
import com.infomedia.abacox.telephonypricing.dto.subdivisionmanager.UpdateSubdivisionManager;
import com.infomedia.abacox.telephonypricing.dto.superclass.ActivationDto;
import com.infomedia.abacox.telephonypricing.db.entity.SubdivisionManager;
import com.infomedia.abacox.telephonypricing.service.SubdivisionManagerService;
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

@RequiredArgsConstructor
@RestController
@Tag(name = "SubdivisionManager", description = "Subdivision Manager API")
@SecurityRequirements({
        @SecurityRequirement(name = "JWT_Token"),
        @SecurityRequirement(name = "Username")
})
@RequestMapping("/api/subdivisionManager")
public class SubdivisionManagerController {

    private final SubdivisionManagerService subdivisionManagerService;
    private final ModelConverter modelConverter;
    private final ExportParamProcessor exportParamProcessor;

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Page<SubdivisionManagerDto> find(@Parameter(hidden = true) @Filter Specification<SubdivisionManager> spec
            , @Parameter(hidden = true) Pageable pageable
            , @RequestParam(required = false) String filter, @RequestParam(required = false) Integer page
            , @RequestParam(required = false) Integer size, @RequestParam(required = false) String sort) {
        return modelConverter.mapPage(subdivisionManagerService.find(spec, pageable), SubdivisionManagerDto.class);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public SubdivisionManagerDto create(@Valid @RequestBody CreateSubdivisionManager createSubdivisionManager) {
        return modelConverter.map(subdivisionManagerService.create(createSubdivisionManager), SubdivisionManagerDto.class);
    }

    @PatchMapping(value = "{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public SubdivisionManagerDto update(@PathVariable("id") Long id, @Valid @RequestBody UpdateSubdivisionManager uDto) {
        return modelConverter.map(subdivisionManagerService.update(id, uDto), SubdivisionManagerDto.class);
    }

    @PatchMapping(value = "/status/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public SubdivisionManagerDto activate(@PathVariable("id") Long id, @Valid @RequestBody ActivationDto activationDto) {
        return modelConverter.map(subdivisionManagerService.changeActivation(id, activationDto.getActive()), SubdivisionManagerDto.class);
    }

    @GetMapping(value = "{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public SubdivisionManagerDto get(@PathVariable("id") Long id) {
        return modelConverter.map(subdivisionManagerService.get(id), SubdivisionManagerDto.class);
    }

    @GetMapping(value = "/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<Resource> exportExcel(@Parameter(hidden = true) @Filter Specification<SubdivisionManager> spec
            , @Parameter(hidden = true) Pageable pageable
            , @RequestParam(required = false) String filter, @RequestParam(required = false) Integer page
            , @RequestParam(required = false) Integer size, @RequestParam(required = false) String sort
            , @RequestParam(required = false) String alternativeHeaders
            , @RequestParam(required = false) String excludeColumns
            , @RequestParam(required = false) String includeColumns
            , @RequestParam(required = false) String valueReplacements) {

        ExcelGeneratorBuilder excelGeneratorBuilder = exportParamProcessor.base64ParamsToExcelGeneratorBuilder(
                alternativeHeaders, excludeColumns, includeColumns, valueReplacements);


        ByteArrayResource resource = subdivisionManagerService.exportExcel(spec, pageable, excelGeneratorBuilder);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=subdivision_managers.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(resource);
    }
}