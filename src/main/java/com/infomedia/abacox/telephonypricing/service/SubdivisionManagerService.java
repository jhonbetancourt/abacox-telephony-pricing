package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.component.export.excel.GenericExcelGenerator;
import com.infomedia.abacox.telephonypricing.dto.subdivisionmanager.CreateSubdivisionManager;
import com.infomedia.abacox.telephonypricing.dto.subdivisionmanager.UpdateSubdivisionManager;
import com.infomedia.abacox.telephonypricing.db.entity.SubdivisionManager;
import com.infomedia.abacox.telephonypricing.repository.SubdivisionManagerRepository;
import com.infomedia.abacox.telephonypricing.service.common.CrudService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;

@Service
public class SubdivisionManagerService extends CrudService<SubdivisionManager, Long, SubdivisionManagerRepository> {
    public SubdivisionManagerService(SubdivisionManagerRepository repository) {
        super(repository);
    }

    public SubdivisionManager create(CreateSubdivisionManager cDto) {
        SubdivisionManager subdivisionManager = SubdivisionManager.builder()
                .subdivisionId(cDto.getSubdivisionId())
                .managerId(cDto.getManagerId())
                .build();

        return save(subdivisionManager);
    }

    public SubdivisionManager update(Long id, UpdateSubdivisionManager uDto) {
        SubdivisionManager subdivisionManager = get(id);
        uDto.getSubdivisionId().ifPresent(subdivisionManager::setSubdivisionId);
        uDto.getManagerId().ifPresent(subdivisionManager::setManagerId);
        return save(subdivisionManager);
    }

    public ByteArrayResource exportExcel(Specification<SubdivisionManager> specification, Pageable pageable, Map<String, String> alternativeHeaders
            , Set<String> excludeColumns, Set<String> includeColumns, Map<String, Map<String, String>> valueReplacements) {
        Page<SubdivisionManager> collection = find(specification, pageable);
        try {
            GenericExcelGenerator.ExcelGeneratorBuilder<?> builder = GenericExcelGenerator.builder(collection.toList());
            if (alternativeHeaders != null) builder.withAlternativeHeaderNames(alternativeHeaders);
            if (excludeColumns != null) builder.withExcludedColumnNames(excludeColumns);
            if (includeColumns != null) builder.withIncludedColumnNames(includeColumns);
            if (valueReplacements != null) builder.withValueReplacements(valueReplacements);
            InputStream inputStream = builder.generateAsInputStream();
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}