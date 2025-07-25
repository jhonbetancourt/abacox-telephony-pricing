package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.component.export.excel.ExcelGeneratorBuilder;
import com.infomedia.abacox.telephonypricing.dto.trunk.CreateTrunk;
import com.infomedia.abacox.telephonypricing.dto.trunk.UpdateTrunk;
import com.infomedia.abacox.telephonypricing.db.entity.Trunk;
import com.infomedia.abacox.telephonypricing.db.repository.TrunkRepository;
import com.infomedia.abacox.telephonypricing.service.common.CrudService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;

@Service
public class TrunkService extends CrudService<Trunk, Long, TrunkRepository> {
    public TrunkService(TrunkRepository repository) {
        super(repository);
    }


    public Trunk create(CreateTrunk cDto){
        Trunk trunk = Trunk.builder()
                .commLocationId(cDto.getCommLocationId())
                .description(cDto.getDescription())
                .name(cDto.getName())
                .operatorId(cDto.getOperatorId())
                .noPbxPrefix(cDto.getNoPbxPrefix())
                .channels(cDto.getChannels())
                .build();

        return save(trunk);
    }

    public Trunk update(Long id, UpdateTrunk uDto){
        Trunk trunk = get(id);
        uDto.getCommLocationId().ifPresent(trunk::setCommLocationId);
        uDto.getDescription().ifPresent(trunk::setDescription);
        uDto.getName().ifPresent(trunk::setName);
        uDto.getOperatorId().ifPresent(trunk::setOperatorId);
        uDto.getNoPbxPrefix().ifPresent(trunk::setNoPbxPrefix);
        uDto.getChannels().ifPresent(trunk::setChannels);
        return save(trunk);
    }

    public ByteArrayResource exportExcel(Specification<Trunk> specification, Pageable pageable, ExcelGeneratorBuilder builder) {
        Page<Trunk> collection = find(specification, pageable);
       try {
            InputStream inputStream = builder.withEntities(collection.toList()).generateAsInputStream();
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}