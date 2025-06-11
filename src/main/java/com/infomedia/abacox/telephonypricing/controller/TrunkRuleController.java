package com.infomedia.abacox.telephonypricing.controller;

import com.infomedia.abacox.telephonypricing.component.export.excel.ParseUtils;
import com.infomedia.abacox.telephonypricing.component.modeltools.ModelConverter;
import com.infomedia.abacox.telephonypricing.dto.trunkrule.TrunkRuleDto;
import com.infomedia.abacox.telephonypricing.dto.trunkrule.CreateTrunkRule;
import com.infomedia.abacox.telephonypricing.dto.trunkrule.UpdateTrunkRule;
import com.infomedia.abacox.telephonypricing.dto.superclass.ActivationDto;
import com.infomedia.abacox.telephonypricing.db.entity.TrunkRule;
import com.infomedia.abacox.telephonypricing.service.TrunkRuleService;
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
@Tag(name = "TrunkRule", description = "Trunk Rule API")
@SecurityRequirements({
        @SecurityRequirement(name = "JWT_Token"),
        @SecurityRequirement(name = "Username")
})
@RequestMapping("/api/trunkRule")
public class TrunkRuleController {

    private final TrunkRuleService trunkRuleService;
    private final ModelConverter modelConverter;

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Page<TrunkRuleDto> find(@Parameter(hidden = true) @Filter Specification<TrunkRule> spec
            , @Parameter(hidden = true) Pageable pageable
            , @RequestParam(required = false) String filter, @RequestParam(required = false) Integer page
            , @RequestParam(required = false) Integer size, @RequestParam(required = false) String sort) {
        return modelConverter.mapPage(trunkRuleService.find(spec, pageable), TrunkRuleDto.class);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public TrunkRuleDto create(@Valid @RequestBody CreateTrunkRule createTrunkRule) {
        return modelConverter.map(trunkRuleService.create(createTrunkRule), TrunkRuleDto.class);
    }

    @PatchMapping(value = "{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public TrunkRuleDto update(@PathVariable("id") Long id, @Valid @RequestBody UpdateTrunkRule uDto) {
        return modelConverter.map(trunkRuleService.update(id, uDto), TrunkRuleDto.class);
    }

    @PatchMapping(value = "/status/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public TrunkRuleDto activate(@PathVariable("id") Long id, @Valid @RequestBody ActivationDto activationDto) {
        return modelConverter.map(trunkRuleService.changeActivation(id, activationDto.getActive()), TrunkRuleDto.class);
    }

    @GetMapping(value = "{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public TrunkRuleDto get(@PathVariable("id") Long id) {
        return modelConverter.map(trunkRuleService.get(id), TrunkRuleDto.class);
    }

    @GetMapping(value = "/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<Resource> exportExcel(@Parameter(hidden = true) @Filter Specification<TrunkRule> spec
            , @Parameter(hidden = true) Pageable pageable
            , @RequestParam(required = false) String filter, @RequestParam(required = false) Integer page
            , @RequestParam(required = false) Integer size, @RequestParam(required = false) String sort
            , @RequestParam(required = false) String alternativeHeaders
            , @RequestParam(required = false) String excludeColumns) {

        ByteArrayResource resource = trunkRuleService.exportExcel(spec, pageable
                , ParseUtils.parseAlternativeHeaders(alternativeHeaders), ParseUtils.parseExcludeColumns(excludeColumns));

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=trunk_rules.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(resource);
    }
}