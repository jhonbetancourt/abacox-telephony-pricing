package com.infomedia.abacox.telephonypricing.controller;

import com.infomedia.abacox.telephonypricing.component.modeltools.ModelConverter;
import com.infomedia.abacox.telephonypricing.dto.jobposition.JobPositionDto;
import com.infomedia.abacox.telephonypricing.dto.jobposition.CreateJobPosition;
import com.infomedia.abacox.telephonypricing.dto.jobposition.UpdateJobPosition;
import com.infomedia.abacox.telephonypricing.dto.superclass.ActivationDto;
import com.infomedia.abacox.telephonypricing.entity.JobPosition;
import com.infomedia.abacox.telephonypricing.service.JobPositionService;
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
@Tag(name = "JobPosition", description = "JobPosition API")
@SecurityRequirements({
        @SecurityRequirement(name = "JWT_Token"),
        @SecurityRequirement(name = "Username")
})
@RequestMapping("/api/jobPosition")
public class JobPositionController {

    private final JobPositionService jobPositionService;
    private final ModelConverter modelConverter;

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Page<JobPositionDto> find(@Parameter(hidden = true) @Filter Specification<JobPosition> spec
            , @Parameter(hidden = true) Pageable pageable
            , @RequestParam(required = false) String filter, @RequestParam(required = false) Integer page
            , @RequestParam(required = false) Integer size, @RequestParam(required = false) String sort) {
        return modelConverter.mapPage(jobPositionService.find(spec, pageable), JobPositionDto.class);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public JobPositionDto create(@Valid @RequestBody CreateJobPosition createJobPosition) {
        return modelConverter.map(jobPositionService.create(createJobPosition), JobPositionDto.class);
    }

    @PatchMapping(value = "{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public JobPositionDto update(@PathVariable("id") Long id, @Valid @RequestBody UpdateJobPosition uDto) {
        return modelConverter.map(jobPositionService.update(id, uDto), JobPositionDto.class);
    }

    @GetMapping(value = "{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    private JobPositionDto get(@PathVariable("id") Long id) {
        return modelConverter.map(jobPositionService.get(id), JobPositionDto.class);
    }

    @PatchMapping(value = "/status/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public JobPositionDto activate(@PathVariable("id") Long id, @Valid @RequestBody ActivationDto activationDto) {
        return modelConverter.map(jobPositionService.changeActivation(id, activationDto.getActive()), JobPositionDto.class);
    }
}