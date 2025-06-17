package com.infomedia.abacox.telephonypricing.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.infomedia.abacox.telephonypricing.component.modeltools.ModelConverter;
import com.infomedia.abacox.telephonypricing.dto.pbxspecialrule.PbxSpecialRuleDto;
import com.infomedia.abacox.telephonypricing.dto.pbxspecialrule.CreatePbxSpecialRule;
import com.infomedia.abacox.telephonypricing.dto.pbxspecialrule.UpdatePbxSpecialRule;
import com.infomedia.abacox.telephonypricing.dto.superclass.ActivationDto;
import com.infomedia.abacox.telephonypricing.db.entity.PbxSpecialRule;
import com.infomedia.abacox.telephonypricing.service.PbxSpecialRuleService;
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
@Tag(name = "PbxSpecialRule", description = "PBX Special Rule API")
@SecurityRequirements({
        @SecurityRequirement(name = "JWT_Token"),
        @SecurityRequirement(name = "Username")
})
@RequestMapping("/api/pbxSpecialRule")
public class PbxSpecialRuleController {

    private final PbxSpecialRuleService pbxSpecialRuleService;
    private final ModelConverter modelConverter;

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Page<PbxSpecialRuleDto> find(@Parameter(hidden = true) @Filter Specification<PbxSpecialRule> spec
            , @Parameter(hidden = true) Pageable pageable
            , @RequestParam(required = false) String filter, @RequestParam(required = false) Integer page
            , @RequestParam(required = false) Integer size, @RequestParam(required = false) String sort) {
        return modelConverter.mapPage(pbxSpecialRuleService.find(spec, pageable), PbxSpecialRuleDto.class);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public PbxSpecialRuleDto create(@Valid @RequestBody CreatePbxSpecialRule createPbxSpecialRule) {
        return modelConverter.map(pbxSpecialRuleService.create(createPbxSpecialRule), PbxSpecialRuleDto.class);
    }

    @PatchMapping(value = "{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public PbxSpecialRuleDto update(@PathVariable("id") Long id, @Valid @RequestBody UpdatePbxSpecialRule uDto) {
        return modelConverter.map(pbxSpecialRuleService.update(id, uDto), PbxSpecialRuleDto.class);
    }

    @PatchMapping(value = "/status/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public PbxSpecialRuleDto activate(@PathVariable("id") Long id, @Valid @RequestBody ActivationDto activationDto) {
        return modelConverter.map(pbxSpecialRuleService.changeActivation(id, activationDto.getActive()), PbxSpecialRuleDto.class);
    }

    @GetMapping(value = "{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public PbxSpecialRuleDto get(@PathVariable("id") Long id) {
        return modelConverter.map(pbxSpecialRuleService.get(id), PbxSpecialRuleDto.class);
    }

    @GetMapping(value = "/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<Resource> exportExcel(@Parameter(hidden = true) @Filter Specification<PbxSpecialRule> spec
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


        ByteArrayResource resource = pbxSpecialRuleService.exportExcel(spec, pageable
                , alternativeHeadersMap, excludeColumnsList, includeColumnsList, valueReplacementsMap);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=pbx_special_rules.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(resource);
    }
}