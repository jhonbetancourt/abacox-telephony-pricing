package com.infomedia.abacox.telephonypricing.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.infomedia.abacox.telephonypricing.component.modeltools.ModelConverter;
import com.infomedia.abacox.telephonypricing.dto.officedetails.OfficeDetailsDto;
import com.infomedia.abacox.telephonypricing.dto.officedetails.CreateOfficeDetails;
import com.infomedia.abacox.telephonypricing.dto.officedetails.UpdateOfficeDetails;
import com.infomedia.abacox.telephonypricing.dto.superclass.ActivationDto;
import com.infomedia.abacox.telephonypricing.db.entity.OfficeDetails;
import com.infomedia.abacox.telephonypricing.service.OfficeDetailsService;
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
@Tag(name = "OfficeDetails", description = "Office Details API")
@SecurityRequirements({
        @SecurityRequirement(name = "JWT_Token"),
        @SecurityRequirement(name = "Username")
})
@RequestMapping("/api/officeDetails")
public class OfficeDetailsController {

    private final OfficeDetailsService officeDetailsService;
    private final ModelConverter modelConverter;

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Page<OfficeDetailsDto> find(@Parameter(hidden = true) @Filter Specification<OfficeDetails> spec
            , @Parameter(hidden = true) Pageable pageable
            , @RequestParam(required = false) String filter, @RequestParam(required = false) Integer page
            , @RequestParam(required = false) Integer size, @RequestParam(required = false) String sort) {
        return modelConverter.mapPage(officeDetailsService.find(spec, pageable), OfficeDetailsDto.class);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public OfficeDetailsDto create(@Valid @RequestBody CreateOfficeDetails createOfficeDetails) {
        return modelConverter.map(officeDetailsService.create(createOfficeDetails), OfficeDetailsDto.class);
    }

    @PatchMapping(value = "{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public OfficeDetailsDto update(@PathVariable("id") Long id, @Valid @RequestBody UpdateOfficeDetails uDto) {
        return modelConverter.map(officeDetailsService.update(id, uDto), OfficeDetailsDto.class);
    }

    @PatchMapping(value = "/status/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public OfficeDetailsDto activate(@PathVariable("id") Long id, @Valid @RequestBody ActivationDto activationDto) {
        return modelConverter.map(officeDetailsService.changeActivation(id, activationDto.getActive()), OfficeDetailsDto.class);
    }

    @GetMapping(value = "{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public OfficeDetailsDto get(@PathVariable("id") Long id) {
        return modelConverter.map(officeDetailsService.get(id), OfficeDetailsDto.class);
    }

    @GetMapping(value = "/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<Resource> exportExcel(@Parameter(hidden = true) @Filter Specification<OfficeDetails> spec
            , @Parameter(hidden = true) Pageable pageable
            , @RequestParam(required = false) String filter, @RequestParam(required = false) Integer page
            , @RequestParam(required = false) Integer size, @RequestParam(required = false) String sort
            , @RequestParam(required = false) String alternativeHeaders
            , @RequestParam(required = false) String excludeColumns
            , @RequestParam(required = false) String includeColumns
            , @RequestParam(required = false) String valueReplacements) {

        Map<String, String> alternativeHeadersMap = modelConverter.convert(alternativeHeaders==null?null:
                new String(Base64.getDecoder().decode(alternativeHeaders)), new TypeReference<Map<String, String>>() {});
        Set<String> excludeColumnsList = modelConverter.convert(excludeColumns==null?null:
                new String(Base64.getDecoder().decode(excludeColumns)), new TypeReference<Set<String>>() {});
        Set<String> includeColumnsList = modelConverter.convert(includeColumns==null?null:
                new String(Base64.getDecoder().decode(includeColumns)), new TypeReference<Set<String>>() {});
        Map<String, Map<String, String>> valueReplacementsMap = modelConverter.convert(valueReplacements==null?null:
                new String(Base64.getDecoder().decode(valueReplacements)), new TypeReference<Map<String, Map<String, String>>>() {});


        ByteArrayResource resource = officeDetailsService.exportExcel(spec, pageable
                , alternativeHeadersMap, excludeColumnsList, includeColumnsList, valueReplacementsMap);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=office_details.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(resource);
    }
}