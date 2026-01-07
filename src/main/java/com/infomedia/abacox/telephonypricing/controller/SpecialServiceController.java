package com.infomedia.abacox.telephonypricing.controller;

import com.infomedia.abacox.telephonypricing.component.export.excel.ExcelGeneratorBuilder;
import com.infomedia.abacox.telephonypricing.component.modeltools.ModelConverter;
import com.infomedia.abacox.telephonypricing.dto.specialservice.SpecialServiceDto;
import com.infomedia.abacox.telephonypricing.dto.specialservice.CreateSpecialService;
import com.infomedia.abacox.telephonypricing.dto.specialservice.UpdateSpecialService;
import com.infomedia.abacox.telephonypricing.dto.superclass.ActivationDto;
import com.infomedia.abacox.telephonypricing.db.entity.SpecialService;
import com.infomedia.abacox.telephonypricing.service.SpecialServiceService;
import com.turkraft.springfilter.boot.Filter;
import com.infomedia.abacox.telephonypricing.dto.generic.ExcelRequest;
import com.infomedia.abacox.telephonypricing.dto.generic.FilterRequest;
import io.swagger.v3.oas.annotations.Parameter;
import org.springdoc.core.annotations.ParameterObject;
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
@Tag(name = "Special Service", description = "Special Service API")
@SecurityRequirements(value = {
        @SecurityRequirement(name = "JWT_Token"),
        @SecurityRequirement(name = "Username"),
        @SecurityRequirement(name = "Tenant_Id")
})
@RequestMapping("/api/specialService")
public class SpecialServiceController {

    private final SpecialServiceService specialServiceService;
    private final ModelConverter modelConverter;

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Page<SpecialServiceDto> find(@Parameter(hidden = true) @Filter Specification<SpecialService> spec
            , @Parameter(hidden = true) Pageable pageable
            , @RequestParam(required = false) String filter, @RequestParam(required = false) Integer page
            , @RequestParam(required = false) Integer size, @RequestParam(required = false) String sort) {
        return modelConverter.mapPage(specialServiceService.find(spec, pageable), SpecialServiceDto.class);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public SpecialServiceDto create(@Valid @RequestBody CreateSpecialService createSpecialService) {
        return modelConverter.map(specialServiceService.create(createSpecialService), SpecialServiceDto.class);
    }

    @PatchMapping(value = "{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public SpecialServiceDto update(@PathVariable("id") Long id, @Valid @RequestBody UpdateSpecialService updateSpecialService) {
        return modelConverter.map(specialServiceService.update(id, updateSpecialService), SpecialServiceDto.class);
    }

    @PatchMapping(value = "/status/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public SpecialServiceDto activate(@PathVariable("id") Long id, @Valid @RequestBody ActivationDto activationDto) {
        return modelConverter.map(specialServiceService.changeActivation(id, activationDto.getActive()), SpecialServiceDto.class);
    }

    @GetMapping(value = "{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public SpecialServiceDto get(@PathVariable("id") Long id) {
        return modelConverter.map(specialServiceService.get(id), SpecialServiceDto.class);
    }

    @GetMapping(value = "/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<Resource> exportExcel(@Parameter(hidden = true) @Filter Specification<SpecialService> spec
            , @Parameter(hidden = true) Pageable pageable
            , @ParameterObject FilterRequest filterRequest
            , @ParameterObject ExcelRequest excelRequest) {

        
        ByteArrayResource resource = specialServiceService.exportExcel(spec, pageable, excelRequest.toExcelGeneratorBuilder());

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=special_services.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(resource);
    }

}