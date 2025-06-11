package com.infomedia.abacox.telephonypricing.controller;

import com.infomedia.abacox.telephonypricing.component.export.excel.ParseUtils;
import com.infomedia.abacox.telephonypricing.component.modeltools.ModelConverter;
import com.infomedia.abacox.telephonypricing.dto.bandindicator.BandIndicatorDto;
import com.infomedia.abacox.telephonypricing.dto.bandindicator.CreateBandIndicator;
import com.infomedia.abacox.telephonypricing.dto.bandindicator.UpdateBandIndicator;
import com.infomedia.abacox.telephonypricing.db.entity.BandIndicator;
import com.infomedia.abacox.telephonypricing.service.BandIndicatorService;
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
@Tag(name = "BandIndicator", description = "BandIndicator API")
@SecurityRequirements({
        @SecurityRequirement(name = "JWT_Token"),
        @SecurityRequirement(name = "Username")
})
@RequestMapping("/api/bandIndicator")
public class BandIndicatorController {

    private final BandIndicatorService bandIndicatorService;
    private final ModelConverter modelConverter;

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Page<BandIndicatorDto> find(@Parameter(hidden = true) @Filter Specification<BandIndicator> spec
            , @Parameter(hidden = true) Pageable pageable
            , @RequestParam(required = false) String filter, @RequestParam(required = false) Integer page
            , @RequestParam(required = false) Integer size, @RequestParam(required = false) String sort) {
        return modelConverter.mapPage(bandIndicatorService.find(spec, pageable), BandIndicatorDto.class);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public BandIndicatorDto create(@Valid @RequestBody CreateBandIndicator createBandIndicator) {
        return modelConverter.map(bandIndicatorService.create(createBandIndicator), BandIndicatorDto.class);
    }

    @PatchMapping(value = "{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public BandIndicatorDto update(@PathVariable("id") Long id, @Valid @RequestBody UpdateBandIndicator uDto) {
        return modelConverter.map(bandIndicatorService.update(id, uDto), BandIndicatorDto.class);
    }

    @GetMapping(value = "{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public BandIndicatorDto get(@PathVariable("id") Long id) {
        return modelConverter.map(bandIndicatorService.get(id), BandIndicatorDto.class);
    }

    @GetMapping(value = "/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<Resource> exportExcel(@Parameter(hidden = true) @Filter Specification<BandIndicator> spec
            , @Parameter(hidden = true) Pageable pageable
            , @RequestParam(required = false) String filter, @RequestParam(required = false) Integer page
            , @RequestParam(required = false) Integer size, @RequestParam(required = false) String sort
            , @RequestParam(required = false) String alternativeHeaders
            , @RequestParam(required = false) String excludeColumns) {

        ByteArrayResource resource = bandIndicatorService.exportExcel(spec, pageable
                , ParseUtils.parseAlternativeHeaders(alternativeHeaders), ParseUtils.parseExcludeColumns(excludeColumns));

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=bands_indicators.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(resource);
    }
}