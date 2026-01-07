package com.infomedia.abacox.telephonypricing.controller;

import com.infomedia.abacox.telephonypricing.component.modeltools.ModelConverter;
import com.infomedia.abacox.telephonypricing.db.entity.FileInfo;
import com.infomedia.abacox.telephonypricing.dto.fileinfo.CreateFileInfo;
import com.infomedia.abacox.telephonypricing.dto.fileinfo.FileInfoDto;
import com.infomedia.abacox.telephonypricing.dto.fileinfo.UpdateFileInfo;
import com.infomedia.abacox.telephonypricing.dto.generic.FilterRequest;
import com.infomedia.abacox.telephonypricing.service.FileInfoService;
import com.turkraft.springfilter.boot.Filter;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@RequiredArgsConstructor
@RestController
@Tag(name = "FileInfo", description = "FileInfo API")
@SecurityRequirements({
        @SecurityRequirement(name = "JWT_Token"),
        @SecurityRequirement(name = "Username")
})
@RequestMapping("/api/fileInfo")
public class FileInfoController {

    private final FileInfoService fileInfoService;
    private final ModelConverter modelConverter;

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Page<FileInfoDto> find(@Parameter(hidden = true) @Filter Specification<FileInfo> spec
            , @Parameter(hidden = true) Pageable pageable
            , @ParameterObject FilterRequest filterRequest) {
        return modelConverter.mapPage(fileInfoService.find(spec, pageable), FileInfoDto.class);
    }

    @GetMapping(value = "{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    private FileInfoDto get(@PathVariable("id") Long id) {
        return modelConverter.map(fileInfoService.get(id), FileInfoDto.class);
    }
}