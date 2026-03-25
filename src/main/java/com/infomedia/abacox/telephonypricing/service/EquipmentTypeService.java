package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.component.export.excel.ExcelGeneratorBuilder;
import com.infomedia.abacox.telephonypricing.db.entity.EquipmentType;
import com.infomedia.abacox.telephonypricing.db.repository.EquipmentTypeRepository;
import com.infomedia.abacox.telephonypricing.dto.equipmenttype.CreateEquipmentType;
import com.infomedia.abacox.telephonypricing.dto.equipmenttype.UpdateEquipmentType;
import com.infomedia.abacox.telephonypricing.service.common.CrudService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;

@Service
public class EquipmentTypeService extends CrudService<EquipmentType, Long, EquipmentTypeRepository> {

    public EquipmentTypeService(EquipmentTypeRepository repository) {
        super(repository);
    }

    public EquipmentType create(CreateEquipmentType cDto) {
        EquipmentType equipmentType = EquipmentType.builder()
                .name(cDto.getName())
                .build();
        return save(equipmentType);
    }

    public EquipmentType update(Long id, UpdateEquipmentType uDto) {
        EquipmentType equipmentType = get(id);
        uDto.getName().ifPresent(equipmentType::setName);
        return save(equipmentType);
    }

    public ByteArrayResource exportExcel(Specification<EquipmentType> specification, Pageable pageable, ExcelGeneratorBuilder builder) {
        Slice<EquipmentType> collection = findAsSlice(specification, pageable);
        try {
            InputStream inputStream = builder.withEntities(collection.toList()).generateAsInputStream();
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
