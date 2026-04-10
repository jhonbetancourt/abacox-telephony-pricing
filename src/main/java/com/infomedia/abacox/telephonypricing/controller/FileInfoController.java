package com.infomedia.abacox.telephonypricing.controller;

import com.infomedia.abacox.telephonypricing.component.modeltools.ModelConverter;
import com.infomedia.abacox.telephonypricing.db.entity.FileInfo;
import com.infomedia.abacox.telephonypricing.dto.fileinfo.FileInfoDto;
import com.infomedia.abacox.telephonypricing.dto.generic.ExcelRequest;
import com.infomedia.abacox.telephonypricing.dto.generic.ExportRequest;
import com.infomedia.abacox.telephonypricing.dto.generic.FilterRequest;
import com.infomedia.abacox.telephonypricing.security.annotation.RequiresPermission;
import com.infomedia.abacox.telephonypricing.service.FileInfoService;
import com.turkraft.springfilter.boot.Filter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RequiredArgsConstructor
@RestController
@Tag(name = "FileInfo", description = "FileInfo API")
@SecurityRequirements(value = {
        @SecurityRequirement(name = "JWT_Token"),
        @SecurityRequirement(name = "Username"),
        @SecurityRequirement(name = "Tenant_Id")
})
@RequestMapping("/api/fileInfo")
public class FileInfoController {

    private final FileInfoService fileInfoService;
    private final ModelConverter modelConverter;

    @RequiresPermission("cdr:read")
    @Operation(summary = "List CDR file info records")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Slice<FileInfoDto> find(@Parameter(hidden = true) @Filter Specification<FileInfo> spec
            , @Parameter(hidden = true) Pageable pageable
            , @ParameterObject FilterRequest filterRequest) {
        return modelConverter.mapSlice(fileInfoService.find(spec, pageable), FileInfoDto.class);
    }

    @RequiresPermission("cdr:read")
    @Operation(summary = "Get CDR file info by ID")
    @GetMapping(value = "{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    private FileInfoDto get(@PathVariable("id") Long id) {
        return modelConverter.map(fileInfoService.get(id), FileInfoDto.class);
    }

    @RequiresPermission("cdr:read")
    @Operation(summary = "Export CDR file info to Excel")
    @GetMapping(value = "/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> exportExcel(
            @Parameter(hidden = true) @Filter Specification<FileInfo> spec,
            @ParameterObject FilterRequest filterRequest,
            @ParameterObject ExportRequest exportRequest,
            @ParameterObject ExcelRequest excelRequest) {

        StreamingResponseBody body = out ->
                fileInfoService.exportExcelStreaming(spec, exportRequest.getSortOrder(),
                        exportRequest.getMaxRows(), out, excelRequest.toExcelGeneratorBuilder());

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=file_info.xlsx")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(body);
    }
}