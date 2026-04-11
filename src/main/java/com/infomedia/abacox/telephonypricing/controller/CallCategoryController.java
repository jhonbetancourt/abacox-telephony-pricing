package com.infomedia.abacox.telephonypricing.controller;

import com.infomedia.abacox.telephonypricing.component.modeltools.ModelConverter;
import com.infomedia.abacox.telephonypricing.dto.callcategory.CallCategoryDto;
import com.infomedia.abacox.telephonypricing.dto.callcategory.CreateCallCategory;
import com.infomedia.abacox.telephonypricing.dto.callcategory.UpdateCallCategory;
import com.infomedia.abacox.telephonypricing.dto.superclass.ActivationDto;
import com.infomedia.abacox.telephonypricing.db.entity.CallCategory;
import com.infomedia.abacox.telephonypricing.security.annotation.RequiresPermission;
import com.infomedia.abacox.telephonypricing.security.permissions.Permissions;
import com.infomedia.abacox.telephonypricing.service.CallCategoryService;
import com.turkraft.springfilter.boot.Filter;
import io.swagger.v3.oas.annotations.Operation;
import com.infomedia.abacox.telephonypricing.dto.generic.ExcelRequest;
import com.infomedia.abacox.telephonypricing.dto.generic.ExportRequest;
import com.infomedia.abacox.telephonypricing.dto.generic.FilterRequest;
import com.infomedia.abacox.telephonypricing.dto.generic.PageableRequest;
import io.swagger.v3.oas.annotations.Parameter;
import org.springdoc.core.annotations.ParameterObject;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RequiredArgsConstructor
@RestController
@Tag(name = "CallCategory", description = "CallCategory API")
@SecurityRequirements(value = {
        @SecurityRequirement(name = "JWT_Token"),
        @SecurityRequirement(name = "Username"),
        @SecurityRequirement(name = "Tenant_Id")
})
@RequestMapping("/api/callCategory")
public class CallCategoryController {

    private final CallCategoryService callCategoryService;
    private final ModelConverter modelConverter;

    @RequiresPermission(Permissions.PRICING_READ)
    @Operation(summary = "List call categories")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Slice<CallCategoryDto> find(@Parameter(hidden = true) @Filter Specification<CallCategory> spec
            , @Parameter(hidden = true) Pageable pageable
            , @ParameterObject FilterRequest filterRequest
            , @ParameterObject PageableRequest pageableRequest) {
        return modelConverter.mapSlice(callCategoryService.findAsSlice(spec, pageable), CallCategoryDto.class);
    }

    @RequiresPermission(Permissions.PRICING_CREATE)
    @Operation(summary = "Create a call category")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public CallCategoryDto create(@Valid @RequestBody CreateCallCategory createCallCategory) {
        return modelConverter.map(callCategoryService.create(createCallCategory), CallCategoryDto.class);
    }

    @RequiresPermission(Permissions.PRICING_UPDATE)
    @Operation(summary = "Update a call category")
    @PatchMapping(value = "{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public CallCategoryDto update(@PathVariable("id") Long id, @Valid @RequestBody UpdateCallCategory uDto) {
        return modelConverter.map(callCategoryService.update(id, uDto), CallCategoryDto.class);
    }

    @RequiresPermission(Permissions.PRICING_READ)
    @Operation(summary = "Get call category by ID")
    @GetMapping(value = "{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public CallCategoryDto get(@PathVariable("id") Long id) {
        return modelConverter.map(callCategoryService.get(id), CallCategoryDto.class);
    }

    @RequiresPermission(Permissions.PRICING_UPDATE)
    @Operation(summary = "Change call category activation status")
    @PatchMapping(value = "/status/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public CallCategoryDto activate(@PathVariable("id") Long id, @Valid @RequestBody ActivationDto activationDto) {
        return modelConverter.map(callCategoryService.changeActivation(id, activationDto.getActive()), CallCategoryDto.class);
    }

    @RequiresPermission(Permissions.PRICING_READ)
    @Operation(summary = "Export call categories to Excel")
    @GetMapping(value = "/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> exportExcel(@Parameter(hidden = true) @Filter Specification<CallCategory> spec,
            @ParameterObject FilterRequest filterRequest,
            @ParameterObject ExportRequest exportRequest,
            @ParameterObject ExcelRequest excelRequest) {

        
        StreamingResponseBody body = out ->
            callCategoryService.exportExcelStreaming(spec, exportRequest.getSortOrder(), exportRequest.getMaxRows(), out, excelRequest.toExcelGeneratorBuilder());

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=call_categories.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }
}