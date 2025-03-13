package com.infomedia.abacox.telephonypricing.controller;

import com.infomedia.abacox.telephonypricing.component.modeltools.ModelConverter;
import com.infomedia.abacox.telephonypricing.dto.bandgroup.BandGroupDto;
import com.infomedia.abacox.telephonypricing.dto.bandgroup.CreateBandGroup;
import com.infomedia.abacox.telephonypricing.dto.bandgroup.UpdateBandGroup;
import com.infomedia.abacox.telephonypricing.dto.superclass.ActivationDto;
import com.infomedia.abacox.telephonypricing.entity.BandGroup;
import com.infomedia.abacox.telephonypricing.service.BandGroupService;
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
@Tag(name = "BandGroup", description = "BandGroup API")
@SecurityRequirements({
        @SecurityRequirement(name = "JWT_Token"),
        @SecurityRequirement(name = "Username")
})
@RequestMapping("/api/bandGroup")
public class BandGroupController {

    private final BandGroupService bandGroupService;
    private final ModelConverter modelConverter;

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Page<BandGroupDto> find(@Parameter(hidden = true) @Filter Specification<BandGroup> spec
            , @Parameter(hidden = true) Pageable pageable
            , @RequestParam(required = false) String filter, @RequestParam(required = false) Integer page
            , @RequestParam(required = false) Integer size, @RequestParam(required = false) String sort) {
        return modelConverter.mapPage(bandGroupService.find(spec, pageable), BandGroupDto.class);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public BandGroupDto create(@Valid @RequestBody CreateBandGroup createBandGroup) {
        return modelConverter.map(bandGroupService.create(createBandGroup), BandGroupDto.class);
    }

    @PatchMapping(value = "{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public BandGroupDto update(@PathVariable("id") Long id, @Valid @RequestBody UpdateBandGroup uDto) {
        return modelConverter.map(bandGroupService.update(id, uDto), BandGroupDto.class);
    }

    @GetMapping(value = "{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    private BandGroupDto get(@PathVariable("id") Long id) {
        return modelConverter.map(bandGroupService.get(id), BandGroupDto.class);
    }
}