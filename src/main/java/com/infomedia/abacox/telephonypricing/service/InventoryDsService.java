package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.component.export.excel.ExcelGeneratorBuilder;
import com.infomedia.abacox.telephonypricing.db.entity.InventoryDs;
import com.infomedia.abacox.telephonypricing.db.repository.InventoryDsRepository;
import com.infomedia.abacox.telephonypricing.dto.inventoryds.CreateInventoryDs;
import com.infomedia.abacox.telephonypricing.dto.inventoryds.UpdateInventoryDs;
import com.infomedia.abacox.telephonypricing.service.common.CrudService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;

@Service
public class InventoryDsService extends CrudService<InventoryDs, Long, InventoryDsRepository> {

    public InventoryDsService(InventoryDsRepository repository) {
        super(repository);
    }

    public InventoryDs create(CreateInventoryDs cDto) {
        InventoryDs inventoryDs = InventoryDs.builder()
                .name(cDto.getName())
                .inventoryEquipmentId(cDto.getInventoryEquipmentId())
                .company(cDto.getCompany())
                .nit(cDto.getNit())
                .subdivisionId(cDto.getSubdivisionId())
                .address(cDto.getAddress())
                .build();
        return save(inventoryDs);
    }

    public InventoryDs update(Long id, UpdateInventoryDs uDto) {
        InventoryDs inventoryDs = get(id);
        uDto.getName().ifPresent(inventoryDs::setName);
        uDto.getInventoryEquipmentId().ifPresent(inventoryDs::setInventoryEquipmentId);
        uDto.getCompany().ifPresent(inventoryDs::setCompany);
        uDto.getNit().ifPresent(inventoryDs::setNit);
        uDto.getSubdivisionId().ifPresent(inventoryDs::setSubdivisionId);
        uDto.getAddress().ifPresent(inventoryDs::setAddress);
        return save(inventoryDs);
    }

    public ByteArrayResource exportExcel(Specification<InventoryDs> specification, Pageable pageable, ExcelGeneratorBuilder builder) {
        Slice<InventoryDs> collection = findAsSlice(specification, pageable);
        try {
            InputStream inputStream = builder.withEntities(collection.toList()).generateAsInputStream();
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
