package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.component.export.excel.ExcelGeneratorBuilder;
import com.infomedia.abacox.telephonypricing.dto.telephonytype.CreateTelephonyType;
import com.infomedia.abacox.telephonypricing.dto.telephonytype.UpdateTelephonyType;
import com.infomedia.abacox.telephonypricing.db.entity.TelephonyType;
import com.infomedia.abacox.telephonypricing.db.repository.TelephonyTypeRepository;
import com.infomedia.abacox.telephonypricing.service.common.CrudService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;

@Service
public class TelephonyTypeService extends CrudService<TelephonyType, Long, TelephonyTypeRepository> {
    public TelephonyTypeService(TelephonyTypeRepository repository) {
        super(repository);
    }

    public TelephonyType create(CreateTelephonyType cDto) {
        TelephonyType telephonyType = TelephonyType.builder()
                .name(cDto.getName())
                .callCategoryId(cDto.getCallCategoryId())
                .usesTrunks(cDto.getUsesTrunks())
                .build();
        return save(telephonyType);
    }

    public TelephonyType update(Long id, UpdateTelephonyType uDto) {
        TelephonyType telephonyType = get(id);
        uDto.getName().ifPresent(telephonyType::setName);
        uDto.getCallCategoryId().ifPresent(telephonyType::setCallCategoryId);
        uDto.getUsesTrunks().ifPresent(telephonyType::setUsesTrunks);
        return save(telephonyType);
    }

    public ByteArrayResource exportExcel(Specification<TelephonyType> specification, Pageable pageable, ExcelGeneratorBuilder builder) {
        Page<TelephonyType> collection = find(specification, pageable);
       try {
            InputStream inputStream = builder.withEntities(collection.toList()).generateAsInputStream();
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}