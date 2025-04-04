package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.component.export.excel.GenericExcelGenerator;
import com.infomedia.abacox.telephonypricing.dto.telephonytype.CreateTelephonyType;
import com.infomedia.abacox.telephonypricing.dto.telephonytype.UpdateTelephonyType;
import com.infomedia.abacox.telephonypricing.entity.TelephonyType;
import com.infomedia.abacox.telephonypricing.repository.TelephonyTypeRepository;
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

    public ByteArrayResource exportExcel(Specification<TelephonyType> specification, Pageable pageable, Map<String, String> alternativeHeaders, Set<String> excludeColumns) {
        Page<TelephonyType> collection = find(specification, pageable);
        try {
            InputStream inputStream = GenericExcelGenerator.generateExcelInputStream(collection.toList()
                    , Set.of(), alternativeHeaders, excludeColumns);
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}