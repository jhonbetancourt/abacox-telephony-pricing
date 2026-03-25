package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.component.export.excel.ExcelGeneratorBuilder;
import com.infomedia.abacox.telephonypricing.db.entity.InventorySupplier;
import com.infomedia.abacox.telephonypricing.db.repository.InventorySupplierRepository;
import com.infomedia.abacox.telephonypricing.dto.inventorysupplier.CreateInventorySupplier;
import com.infomedia.abacox.telephonypricing.dto.inventorysupplier.UpdateInventorySupplier;
import com.infomedia.abacox.telephonypricing.service.common.CrudService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;

@Service
public class InventorySupplierService extends CrudService<InventorySupplier, Long, InventorySupplierRepository> {

    public InventorySupplierService(InventorySupplierRepository repository) {
        super(repository);
    }

    public InventorySupplier create(CreateInventorySupplier cDto) {
        InventorySupplier inventorySupplier = InventorySupplier.builder()
                .name(cDto.getName())
                .inventoryEquipmentId(cDto.getInventoryEquipmentId())
                .company(cDto.getCompany())
                .nit(cDto.getNit())
                .subdivisionId(cDto.getSubdivisionId())
                .address(cDto.getAddress())
                .build();
        return save(inventorySupplier);
    }

    public InventorySupplier update(Long id, UpdateInventorySupplier uDto) {
        InventorySupplier inventorySupplier = get(id);
        uDto.getName().ifPresent(inventorySupplier::setName);
        uDto.getInventoryEquipmentId().ifPresent(inventorySupplier::setInventoryEquipmentId);
        uDto.getCompany().ifPresent(inventorySupplier::setCompany);
        uDto.getNit().ifPresent(inventorySupplier::setNit);
        uDto.getSubdivisionId().ifPresent(inventorySupplier::setSubdivisionId);
        uDto.getAddress().ifPresent(inventorySupplier::setAddress);
        return save(inventorySupplier);
    }

    public ByteArrayResource exportExcel(Specification<InventorySupplier> specification, Pageable pageable, ExcelGeneratorBuilder builder) {
        Slice<InventorySupplier> collection = findAsSlice(specification, pageable);
        try {
            InputStream inputStream = builder.withEntities(collection.toList()).generateAsInputStream();
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
