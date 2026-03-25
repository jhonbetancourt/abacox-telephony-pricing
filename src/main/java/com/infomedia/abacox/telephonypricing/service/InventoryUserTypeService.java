package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.component.export.excel.ExcelGeneratorBuilder;
import com.infomedia.abacox.telephonypricing.db.entity.InventoryUserType;
import com.infomedia.abacox.telephonypricing.db.repository.InventoryUserTypeRepository;
import com.infomedia.abacox.telephonypricing.dto.inventoryusertype.CreateInventoryUserType;
import com.infomedia.abacox.telephonypricing.dto.inventoryusertype.UpdateInventoryUserType;
import com.infomedia.abacox.telephonypricing.service.common.CrudService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;

@Service
public class InventoryUserTypeService extends CrudService<InventoryUserType, Long, InventoryUserTypeRepository> {

    public InventoryUserTypeService(InventoryUserTypeRepository repository) {
        super(repository);
    }

    public InventoryUserType create(CreateInventoryUserType cDto) {
        InventoryUserType inventoryUserType = InventoryUserType.builder()
                .name(cDto.getName())
                .build();
        return save(inventoryUserType);
    }

    public InventoryUserType update(Long id, UpdateInventoryUserType uDto) {
        InventoryUserType inventoryUserType = get(id);
        uDto.getName().ifPresent(inventoryUserType::setName);
        return save(inventoryUserType);
    }

    public ByteArrayResource exportExcel(Specification<InventoryUserType> specification, Pageable pageable, ExcelGeneratorBuilder builder) {
        Slice<InventoryUserType> collection = findAsSlice(specification, pageable);
        try {
            InputStream inputStream = builder.withEntities(collection.toList()).generateAsInputStream();
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
