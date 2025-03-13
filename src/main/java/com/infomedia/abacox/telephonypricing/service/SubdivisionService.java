package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.dto.subdivision.CreateSubdivision;
import com.infomedia.abacox.telephonypricing.dto.subdivision.UpdateSubdivision;
import com.infomedia.abacox.telephonypricing.entity.Subdivision;
import com.infomedia.abacox.telephonypricing.repository.SubdivisionRepository;
import com.infomedia.abacox.telephonypricing.service.common.CrudService;
import org.springframework.stereotype.Service;

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
}
