package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.dto.jobposition.CreateJobPosition;
import com.infomedia.abacox.telephonypricing.dto.jobposition.UpdateJobPosition;
import com.infomedia.abacox.telephonypricing.entity.JobPosition;
import com.infomedia.abacox.telephonypricing.repository.JobPositionRepository;
import com.infomedia.abacox.telephonypricing.service.common.CrudService;
import org.springframework.stereotype.Service;

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
}
