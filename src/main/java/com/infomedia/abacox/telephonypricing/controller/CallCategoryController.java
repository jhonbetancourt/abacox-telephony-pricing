package com.infomedia.abacox.telephonypricing.controller;

import com.infomedia.abacox.telephonypricing.component.export.excel.ParseUtils;
import com.infomedia.abacox.telephonypricing.component.modeltools.ModelConverter;
import com.infomedia.abacox.telephonypricing.dto.callcategory.CallCategoryDto;
import com.infomedia.abacox.telephonypricing.dto.callcategory.CreateCallCategory;
import com.infomedia.abacox.telephonypricing.dto.callcategory.UpdateCallCategory;
import com.infomedia.abacox.telephonypricing.dto.superclass.ActivationDto;
import com.infomedia.abacox.telephonypricing.entity.CallCategory;
import com.infomedia.abacox.telephonypricing.service.CallCategoryService;
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
@Tag(name = "CallCategory", description = "CallCategory API")
@SecurityRequirements({
        @SecurityRequirement(name = "JWT_Token"),
        @SecurityRequirement(name = "Username")
})
@RequestMapping("/api/callCategory")
public class CallCategoryController {

    private final CallCategoryService callCategoryService;
    private final ModelConverter modelConverter;

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Page<CallCategoryDto> find(@Parameter(hidden = true) @Filter Specification<CallCategory> spec
            , @Parameter(hidden = true) Pageable pageable
            , @RequestParam(required = false) String filter, @RequestParam(required = false) Integer page
            , @RequestParam(required = false) Integer size, @RequestParam(required = false) String sort) {
        return modelConverter.mapPage(callCategoryService.find(spec, pageable), CallCategoryDto.class);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public CallCategoryDto create(@Valid @RequestBody CreateCallCategory createCallCategory) {
        return modelConverter.map(callCategoryService.create(createCallCategory), CallCategoryDto.class);
    }

    @PatchMapping(value = "{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public CallCategoryDto update(@PathVariable("id") Long id, @Valid @RequestBody UpdateCallCategory uDto) {
        return modelConverter.map(callCategoryService.update(id, uDto), CallCategoryDto.class);
    }

    @GetMapping(value = "{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public CallCategoryDto get(@PathVariable("id") Long id) {
        return modelConverter.map(callCategoryService.get(id), CallCategoryDto.class);
    }

    @PatchMapping(value = "/status/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public CallCategoryDto activate(@PathVariable("id") Long id, @Valid @RequestBody ActivationDto activationDto) {
        return modelConverter.map(callCategoryService.changeActivation(id, activationDto.getActive()), CallCategoryDto.class);
    }

    @GetMapping(value = "/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<Resource> exportExcel(@Parameter(hidden = true) @Filter Specification<CallCategory> spec
            , @Parameter(hidden = true) Pageable pageable
            , @RequestParam(required = false) String filter, @RequestParam(required = false) Integer page
            , @RequestParam(required = false) Integer size, @RequestParam(required = false) String sort
            , @RequestParam(required = false) String alternativeHeaders
            , @RequestParam(required = false) String excludeColumns) {

        ByteArrayResource resource = callCategoryService.exportExcel(spec, pageable
                , ParseUtils.parseAlternativeHeaders(alternativeHeaders), ParseUtils.parseExcludeColumns(excludeColumns));

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=call_categories.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(resource);
    }
}