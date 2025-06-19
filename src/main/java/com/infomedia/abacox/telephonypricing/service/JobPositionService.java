package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.component.export.excel.ExcelGeneratorBuilder;
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

    public ByteArrayResource exportExcel(Specification<JobPosition> specification, Pageable pageable, ExcelGeneratorBuilder builder) {
        Page<JobPosition> collection = find(specification, pageable);
       try {
            InputStream inputStream = builder.withEntities(collection.toList()).generateAsInputStream();
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}