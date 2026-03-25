package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.component.export.excel.ExcelGeneratorBuilder;
import com.infomedia.abacox.telephonypricing.db.entity.InventoryWorkOrderType;
import com.infomedia.abacox.telephonypricing.db.repository.InventoryWorkOrderTypeRepository;
import com.infomedia.abacox.telephonypricing.dto.inventoryworkordertype.CreateInventoryWorkOrderType;
import com.infomedia.abacox.telephonypricing.dto.inventoryworkordertype.UpdateInventoryWorkOrderType;
import com.infomedia.abacox.telephonypricing.service.common.CrudService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;

@Service
public class InventoryWorkOrderTypeService extends CrudService<InventoryWorkOrderType, Long, InventoryWorkOrderTypeRepository> {

    public InventoryWorkOrderTypeService(InventoryWorkOrderTypeRepository repository) {
        super(repository);
    }

    public InventoryWorkOrderType create(CreateInventoryWorkOrderType cDto) {
        InventoryWorkOrderType inventoryWorkOrderType = InventoryWorkOrderType.builder()
                .name(cDto.getName())
                .build();
        return save(inventoryWorkOrderType);
    }

    public InventoryWorkOrderType update(Long id, UpdateInventoryWorkOrderType uDto) {
        InventoryWorkOrderType inventoryWorkOrderType = get(id);
        uDto.getName().ifPresent(inventoryWorkOrderType::setName);
        return save(inventoryWorkOrderType);
    }

    public ByteArrayResource exportExcel(Specification<InventoryWorkOrderType> specification, Pageable pageable, ExcelGeneratorBuilder builder) {
        Slice<InventoryWorkOrderType> collection = findAsSlice(specification, pageable);
        try {
            InputStream inputStream = builder.withEntities(collection.toList()).generateAsInputStream();
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
