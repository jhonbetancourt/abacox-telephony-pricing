package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.component.export.excel.GenericExcelGenerator;
import com.infomedia.abacox.telephonypricing.dto.jobposition.CreateJobPosition;
import com.infomedia.abacox.telephonypricing.dto.jobposition.UpdateJobPosition;
import com.infomedia.abacox.telephonypricing.db.entity.JobPosition;
import com.infomedia.abacox.telephonypricing.repository.JobPositionRepository;
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
public class JobPositionService extends CrudService<JobPosition, Long, JobPositionRepository> {
    public JobPositionService(JobPositionRepository repository) {
        super(repository);
    }

    public JobPosition create(CreateJobPosition uDto){
        JobPosition jobPosition = JobPosition.builder()
                .name(uDto.getName())
                .build();

        return save(jobPosition);
    }

    public JobPosition update(Long id, UpdateJobPosition uDto){
        JobPosition jobPosition = get(id);
        uDto.getName().ifPresent(jobPosition::setName);
        return save(jobPosition);
    }

    public ByteArrayResource exportExcel(Specification<JobPosition> specification, Pageable pageable, Map<String, String> alternativeHeaders
            , Set<String> excludeColumns, Set<String> includeColumns, Map<String, Map<String, String>> valueReplacements) {
        Page<JobPosition> collection = find(specification, pageable);
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