package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.component.export.excel.GenericExcelGenerator;
import com.infomedia.abacox.telephonypricing.dto.subdivision.CreateSubdivision;
import com.infomedia.abacox.telephonypricing.dto.subdivision.UpdateSubdivision;
import com.infomedia.abacox.telephonypricing.db.entity.Subdivision;
import com.infomedia.abacox.telephonypricing.repository.SubdivisionRepository;
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
public class SubdivisionService extends CrudService<Subdivision, Long, SubdivisionRepository> {
    public SubdivisionService(SubdivisionRepository repository) {
        super(repository);
    }

    public Subdivision create(CreateSubdivision uDto){
        Subdivision subdivision = Subdivision.builder()
                .parentSubdivisionId(uDto.getParentSubdivisionId())
                .name(uDto.getName())
                .build();

        return save(subdivision);
    }

    public Subdivision update(Long id, UpdateSubdivision uDto){
        Subdivision subdivision = get(id);
        uDto.getParentSubdivisionId().ifPresent(subdivision::setParentSubdivisionId);
        uDto.getName().ifPresent(subdivision::setName);
        return save(subdivision);
    }

    public ByteArrayResource exportExcel(Specification<Subdivision> specification, Pageable pageable, Map<String, String> alternativeHeaders
            , Set<String> excludeColumns, Set<String> includeColumns, Map<String, Map<String, String>> valueReplacements) {
        Page<Subdivision> collection = find(specification, pageable);
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