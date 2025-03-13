package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.dto.planttype.CreatePlantType;
import com.infomedia.abacox.telephonypricing.dto.planttype.UpdatePlantType;
import com.infomedia.abacox.telephonypricing.entity.PlantType;
import com.infomedia.abacox.telephonypricing.repository.PlantTypeRepository;
import com.infomedia.abacox.telephonypricing.service.common.CrudService;
import org.springframework.stereotype.Service;

@Service
public class PlantTypeService extends CrudService<PlantType, Long, PlantTypeRepository> {
    public PlantTypeService(PlantTypeRepository repository) {
        super(repository);
    }

    public PlantType create(CreatePlantType cDto) {
        PlantType plantType = PlantType
                .builder()
                .name(cDto.getName())
                .build();
        return save(plantType);
    }

    public PlantType update(Long id, UpdatePlantType uDto) {
        PlantType plantType = get(id);
        uDto.getName().ifPresent(plantType::setName);
        return save(plantType);
    }
}