package com.infomedia.abacox.telephonypricing.controller;

import com.infomedia.abacox.telephonypricing.component.export.excel.ExcelGeneratorBuilder;
import com.infomedia.abacox.telephonypricing.component.export.excel.ExportParamProcessor;
import com.infomedia.abacox.telephonypricing.component.modeltools.ModelConverter;
import com.infomedia.abacox.telephonypricing.dto.operator.OperatorDto;
import com.infomedia.abacox.telephonypricing.dto.operator.CreateOperator;
import com.infomedia.abacox.telephonypricing.dto.operator.UpdateOperator;
import com.infomedia.abacox.telephonypricing.dto.superclass.ActivationDto;
import com.infomedia.abacox.telephonypricing.db.entity.Operator;
import com.infomedia.abacox.telephonypricing.service.OperatorService;
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
@Tag(name = "Operator", description = "Operator API")
@SecurityRequirements({
        @SecurityRequirement(name = "JWT_Token"),
        @SecurityRequirement(name = "Username")
})
@RequestMapping("/api/operator")
public class OperatorController {

    private final OperatorService operatorService;
    private final ModelConverter modelConverter;
    private final ExportParamProcessor exportParamProcessor;

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Page<OperatorDto> find(@Parameter(hidden = true) @Filter Specification<Operator> spec
            , @Parameter(hidden = true) Pageable pageable
            , @RequestParam(required = false) String filter, @RequestParam(required = false) Integer page
            , @RequestParam(required = false) Integer size, @RequestParam(required = false) String sort) {
        return modelConverter.mapPage(operatorService.find(spec, pageable), OperatorDto.class);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public OperatorDto create(@Valid @RequestBody CreateOperator createOperator) {
        return modelConverter.map(operatorService.create(createOperator), OperatorDto.class);
    }

    @PatchMapping(value = "{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public OperatorDto update(@PathVariable("id") Long id, @Valid @RequestBody UpdateOperator uDto) {
        return modelConverter.map(operatorService.update(id, uDto), OperatorDto.class);
    }

    @GetMapping(value = "{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public OperatorDto get(@PathVariable("id") Long id) {
        return modelConverter.map(operatorService.get(id), OperatorDto.class);
    }

    @PatchMapping(value = "/status/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public OperatorDto activate(@PathVariable("id") Long id, @Valid @RequestBody ActivationDto activationDto) {
        return modelConverter.map(operatorService.changeActivation(id, activationDto.getActive()), OperatorDto.class);
    }

    @GetMapping(value = "/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<Resource> exportExcel(@Parameter(hidden = true) @Filter Specification<Operator> spec
            , @Parameter(hidden = true) Pageable pageable
            , @RequestParam(required = false) String filter, @RequestParam(required = false) Integer page
            , @RequestParam(required = false) Integer size, @RequestParam(required = false) String sort
            , @RequestParam(required = false) String alternativeHeaders
            , @RequestParam(required = false) String excludeColumns
            , @RequestParam(required = false) String includeColumns
            , @RequestParam(required = false) String valueReplacements) {

        ExcelGeneratorBuilder excelGeneratorBuilder = exportParamProcessor.base64ParamsToExcelGeneratorBuilder(
                alternativeHeaders, excludeColumns, includeColumns, valueReplacements);


        ByteArrayResource resource = operatorService.exportExcel(spec, pageable, excelGeneratorBuilder);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=operators.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(resource);
    }
}