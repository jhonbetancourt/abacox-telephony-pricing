package com.infomedia.abacox.telephonypricing.controller;

import com.infomedia.abacox.telephonypricing.component.modeltools.ModelConverter;
import com.infomedia.abacox.telephonypricing.db.entity.SpecialExtension;
import com.infomedia.abacox.telephonypricing.dto.generic.ExcelRequest;
import com.infomedia.abacox.telephonypricing.dto.generic.FilterRequest;
import com.infomedia.abacox.telephonypricing.dto.specialextension.CreateSpecialExtension;
import com.infomedia.abacox.telephonypricing.dto.specialextension.SpecialExtensionDto;
import com.infomedia.abacox.telephonypricing.dto.specialextension.UpdateSpecialExtension;
import com.infomedia.abacox.telephonypricing.dto.superclass.ActivationDto;
import com.infomedia.abacox.telephonypricing.service.SpecialExtensionService;
import com.turkraft.springfilter.boot.Filter;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
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
@Tag(name = "Special Extension", description = "Special Extension API")
@SecurityRequirements({
        @SecurityRequirement(name = "JWT_Token"),
        @SecurityRequirement(name = "Username")
})
@RequestMapping("/api/special-extension")
public class SpecialExtensionController {

    private final SpecialExtensionService specialExtensionService;
    private final ModelConverter modelConverter;

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Page<SpecialExtensionDto> find(@Parameter(hidden = true) @Filter Specification<SpecialExtension> spec,
                                          @Parameter(hidden = true) Pageable pageable,
                                          @ParameterObject FilterRequest filterRequest) {
        return modelConverter.mapPage(specialExtensionService.find(spec, pageable), SpecialExtensionDto.class);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public SpecialExtensionDto create(@Valid @RequestBody CreateSpecialExtension createDto) {
        return modelConverter.map(specialExtensionService.create(createDto), SpecialExtensionDto.class);
    }

    @PatchMapping(value = "{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public SpecialExtensionDto update(@PathVariable("id") Long id, @Valid @RequestBody UpdateSpecialExtension uDto) {
        return modelConverter.map(specialExtensionService.update(id, uDto), SpecialExtensionDto.class);
    }

    @GetMapping(value = "{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public SpecialExtensionDto get(@PathVariable("id") Long id) {
        return modelConverter.map(specialExtensionService.get(id), SpecialExtensionDto.class);
    }

    @PatchMapping(value = "/status/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public SpecialExtensionDto activate(@PathVariable("id") Long id, @Valid @RequestBody ActivationDto activationDto) {
        return modelConverter.map(specialExtensionService.changeActivation(id, activationDto.getActive()), SpecialExtensionDto.class);
    }

    @GetMapping(value = "/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<Resource> exportExcel(@Parameter(hidden = true) @Filter Specification<SpecialExtension> spec,
                                                @Parameter(hidden = true) Pageable pageable,
                                                @ParameterObject FilterRequest filterRequest,
                                                @ParameterObject ExcelRequest excelRequest) {

        ByteArrayResource resource = specialExtensionService.exportExcel(spec, pageable, excelRequest.toExcelGeneratorBuilder());

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=special-extensions.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(resource);
    }
}