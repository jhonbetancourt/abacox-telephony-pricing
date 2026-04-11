package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.component.export.excel.ExcelGeneratorBuilder;
import com.infomedia.abacox.telephonypricing.db.entity.CdrLoadControl;
import com.infomedia.abacox.telephonypricing.db.repository.CdrLoadControlRepository;
import com.infomedia.abacox.telephonypricing.dto.cdrloadcontrol.CreateCdrLoadControl;
import com.infomedia.abacox.telephonypricing.dto.cdrloadcontrol.UpdateCdrLoadControl;
import com.infomedia.abacox.telephonypricing.service.common.CrudService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;

@Service
public class CdrLoadControlService extends CrudService<CdrLoadControl, Long, CdrLoadControlRepository> {

    public CdrLoadControlService(CdrLoadControlRepository repository) {
        super(repository);
    }

    public CdrLoadControl create(CreateCdrLoadControl cDto) {
        CdrLoadControl entity = CdrLoadControl.builder()
                .name(cDto.getName())
                .plantTypeId(cDto.getPlantTypeId())
                .build();
        return save(entity);
    }

    public CdrLoadControl update(Long id, UpdateCdrLoadControl uDto) {
        CdrLoadControl entity = get(id);
        uDto.getName().ifPresent(entity::setName);
        uDto.getPlantTypeId().ifPresent(entity::setPlantTypeId);
        return save(entity);
    }

    public ByteArrayResource exportExcel(Specification<CdrLoadControl> specification, Pageable pageable, ExcelGeneratorBuilder builder) {
        Slice<CdrLoadControl> collection = findAsSlice(specification, pageable);
        try {
            InputStream inputStream = builder.withEntities(collection.toList()).generateAsInputStream();
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
