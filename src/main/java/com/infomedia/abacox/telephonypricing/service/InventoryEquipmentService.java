package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.component.export.excel.ExcelGeneratorBuilder;
import com.infomedia.abacox.telephonypricing.db.entity.InventoryEquipment;
import com.infomedia.abacox.telephonypricing.db.repository.InventoryEquipmentRepository;
import com.infomedia.abacox.telephonypricing.dto.inventoryequipment.CreateInventoryEquipment;
import com.infomedia.abacox.telephonypricing.dto.inventoryequipment.UpdateInventoryEquipment;
import com.infomedia.abacox.telephonypricing.service.common.CrudService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;

import java.io.IOException;
import java.io.InputStream;

@Service
public class InventoryEquipmentService extends CrudService<InventoryEquipment, Long, InventoryEquipmentRepository> {

    public InventoryEquipmentService(InventoryEquipmentRepository repository) {
        super(repository);
    }

    public InventoryEquipment create(CreateInventoryEquipment cDto) {
        InventoryEquipment inventoryEquipment = InventoryEquipment.builder()
                .name(cDto.getName())
                .parentEquipmentId(cDto.getParentEquipmentId())
                .valueTt(cDto.getValueTt())
                .valueInfomedia(cDto.getValueInfomedia())
                .build();
        return save(inventoryEquipment);
    }

    public InventoryEquipment update(Long id, UpdateInventoryEquipment uDto) {
        InventoryEquipment inventoryEquipment = get(id);
        uDto.getName().ifPresent(inventoryEquipment::setName);
        uDto.getParentEquipmentId().ifPresent(inventoryEquipment::setParentEquipmentId);
        uDto.getValueTt().ifPresent(inventoryEquipment::setValueTt);
        uDto.getValueInfomedia().ifPresent(inventoryEquipment::setValueInfomedia);
        return save(inventoryEquipment);
    }

    public ByteArrayResource exportExcel(Specification<InventoryEquipment> specification, Pageable pageable, ExcelGeneratorBuilder builder) {
        Slice<InventoryEquipment> collection = findAsSlice(specification, pageable);
        try {
            InputStream inputStream = builder.withEntities(collection.toList()).generateAsInputStream();
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
