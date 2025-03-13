package com.infomedia.abacox.telephonypricing.controller;

import com.infomedia.abacox.telephonypricing.component.modeltools.ModelConverter;
import com.infomedia.abacox.telephonypricing.dto.subdivision.SubdivisionDto;
import com.infomedia.abacox.telephonypricing.dto.subdivision.CreateSubdivision;
import com.infomedia.abacox.telephonypricing.dto.subdivision.UpdateSubdivision;
import com.infomedia.abacox.telephonypricing.dto.superclass.ActivationDto;
import com.infomedia.abacox.telephonypricing.entity.Subdivision;
import com.infomedia.abacox.telephonypricing.service.SubdivisionService;
import com.turkraft.springfilter.boot.Filter;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

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
}