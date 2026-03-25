package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.component.export.excel.ExcelGeneratorBuilder;
import com.infomedia.abacox.telephonypricing.db.entity.InventoryOwner;
import com.infomedia.abacox.telephonypricing.db.repository.InventoryOwnerRepository;
import com.infomedia.abacox.telephonypricing.dto.inventoryowner.CreateInventoryOwner;
import com.infomedia.abacox.telephonypricing.dto.inventoryowner.UpdateInventoryOwner;
import com.infomedia.abacox.telephonypricing.service.common.CrudService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;

@Service
public class InventoryOwnerService extends CrudService<InventoryOwner, Long, InventoryOwnerRepository> {

    public InventoryOwnerService(InventoryOwnerRepository repository) {
        super(repository);
    }

    public InventoryOwner create(CreateInventoryOwner cDto) {
        InventoryOwner inventoryOwner = InventoryOwner.builder()
                .name(cDto.getName())
                .build();
        return save(inventoryOwner);
    }

    public InventoryOwner update(Long id, UpdateInventoryOwner uDto) {
        InventoryOwner inventoryOwner = get(id);
        uDto.getName().ifPresent(inventoryOwner::setName);
        return save(inventoryOwner);
    }

    public ByteArrayResource exportExcel(Specification<InventoryOwner> specification, Pageable pageable, ExcelGeneratorBuilder builder) {
        Slice<InventoryOwner> collection = findAsSlice(specification, pageable);
        try {
            InputStream inputStream = builder.withEntities(collection.toList()).generateAsInputStream();
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
