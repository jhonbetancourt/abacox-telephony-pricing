package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.component.export.excel.GenericExcelGenerator;
import com.infomedia.abacox.telephonypricing.dto.planttype.CreatePlantType;
import com.infomedia.abacox.telephonypricing.dto.planttype.UpdatePlantType;
import com.infomedia.abacox.telephonypricing.db.entity.PlantType;
import com.infomedia.abacox.telephonypricing.repository.PlantTypeRepository;
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

    public ByteArrayResource exportExcel(Specification<PlantType> specification, Pageable pageable, Map<String, String> alternativeHeaders, Set<String> excludeColumns) {
        Page<PlantType> collection = find(specification, pageable);
        try {
            InputStream inputStream = GenericExcelGenerator.generateExcelInputStream(collection.toList()
                    , Set.of(), alternativeHeaders, excludeColumns);
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}