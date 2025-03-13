package com.infomedia.abacox.telephonypricing.controller;

import com.infomedia.abacox.telephonypricing.component.modeltools.ModelConverter;
import com.infomedia.abacox.telephonypricing.dto.origincountry.OriginCountryDto;
import com.infomedia.abacox.telephonypricing.dto.origincountry.CreateOriginCountry;
import com.infomedia.abacox.telephonypricing.dto.origincountry.UpdateOriginCountry;
import com.infomedia.abacox.telephonypricing.dto.superclass.ActivationDto;
import com.infomedia.abacox.telephonypricing.entity.OriginCountry;
import com.infomedia.abacox.telephonypricing.service.OriginCountryService;
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
@Tag(name = "OriginCountry", description = "OriginCountry API")
@SecurityRequirements({
        @SecurityRequirement(name = "JWT_Token"),
        @SecurityRequirement(name = "Username")
})
@RequestMapping("/api/originCountry")
public class OriginCountryController {

    private final OriginCountryService originCountryService;
    private final ModelConverter modelConverter;

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Page<OriginCountryDto> find(@Parameter(hidden = true) @Filter Specification<OriginCountry> spec
            , @Parameter(hidden = true) Pageable pageable
            , @RequestParam(required = false) String filter, @RequestParam(required = false) Integer page
            , @RequestParam(required = false) Integer size, @RequestParam(required = false) String sort) {
        return modelConverter.mapPage(originCountryService.find(spec, pageable), OriginCountryDto.class);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public OriginCountryDto create(@Valid @RequestBody CreateOriginCountry createOriginCountry) {
        return modelConverter.map(originCountryService.create(createOriginCountry), OriginCountryDto.class);
    }

    @PatchMapping(value = "{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public OriginCountryDto update(@PathVariable("id") Long id, @Valid @RequestBody UpdateOriginCountry uDto) {
        return modelConverter.map(originCountryService.update(id, uDto), OriginCountryDto.class);
    }

    @PatchMapping(value = "/status/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public OriginCountryDto activate(@PathVariable("id") Long id, @Valid @RequestBody ActivationDto activationDto) {
        return modelConverter.map(originCountryService.changeActivation(id, activationDto.getActive()), OriginCountryDto.class);
    }

    @GetMapping(value = "{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    private OriginCountryDto get(@PathVariable("id") Long id) {
        return modelConverter.map(originCountryService.get(id), OriginCountryDto.class);
    }
}