package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.component.export.excel.ExcelGeneratorBuilder;
import com.infomedia.abacox.telephonypricing.db.entity.InventoryAdditionalService;
import com.infomedia.abacox.telephonypricing.db.repository.InventoryAdditionalServiceRepository;
import com.infomedia.abacox.telephonypricing.dto.inventoryadditionalservice.CreateInventoryAdditionalService;
import com.infomedia.abacox.telephonypricing.dto.inventoryadditionalservice.UpdateInventoryAdditionalService;
import com.infomedia.abacox.telephonypricing.service.common.CrudService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;

@Service
public class InventoryAdditionalServiceService extends CrudService<InventoryAdditionalService, Long, InventoryAdditionalServiceRepository> {

    public InventoryAdditionalServiceService(InventoryAdditionalServiceRepository repository) {
        super(repository);
    }

    public InventoryAdditionalService create(CreateInventoryAdditionalService cDto) {
        InventoryAdditionalService inventoryAdditionalService = InventoryAdditionalService.builder()
                .name(cDto.getName())
                .build();
        return save(inventoryAdditionalService);
    }

    public InventoryAdditionalService update(Long id, UpdateInventoryAdditionalService uDto) {
        InventoryAdditionalService inventoryAdditionalService = get(id);
        uDto.getName().ifPresent(inventoryAdditionalService::setName);
        return save(inventoryAdditionalService);
    }

    public ByteArrayResource exportExcel(Specification<InventoryAdditionalService> specification, Pageable pageable, ExcelGeneratorBuilder builder) {
        Slice<InventoryAdditionalService> collection = findAsSlice(specification, pageable);
        try {
            InputStream inputStream = builder.withEntities(collection.toList()).generateAsInputStream();
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
