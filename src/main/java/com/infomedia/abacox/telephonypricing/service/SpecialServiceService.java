package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.component.export.excel.GenericExcelGenerator;
import com.infomedia.abacox.telephonypricing.dto.specialservice.CreateSpecialService; // Assuming DTO exists
import com.infomedia.abacox.telephonypricing.dto.specialservice.UpdateSpecialService; // Assuming DTO exists
import com.infomedia.abacox.telephonypricing.db.entity.SpecialService;
import com.infomedia.abacox.telephonypricing.repository.SpecialServiceRepository; // Assuming Repository exists
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
public class SpecialServiceService extends CrudService<SpecialService, Long, SpecialServiceRepository> {
    public SpecialServiceService(SpecialServiceRepository repository) {
        super(repository);
    }


    public SpecialService create(CreateSpecialService cDto){
        SpecialService specialService = SpecialService.builder()
                .indicatorId(cDto.getIndicatorId())
                .phoneNumber(cDto.getPhoneNumber())
                .value(cDto.getValue())
                .vatAmount(cDto.getVatAmount())
                .vatIncluded(cDto.getVatIncluded())
                .description(cDto.getDescription())
                .originCountryId(cDto.getOriginCountryId())
                .build();

        return save(specialService);
    }

    public SpecialService update(Long id, UpdateSpecialService uDto){
        SpecialService specialService = get(id);
        uDto.getIndicatorId().ifPresent(specialService::setIndicatorId);
        uDto.getPhoneNumber().ifPresent(specialService::setPhoneNumber);
        uDto.getValue().ifPresent(specialService::setValue);
        uDto.getVatAmount().ifPresent(specialService::setVatAmount);
        uDto.getVatIncluded().ifPresent(specialService::setVatIncluded);
        uDto.getDescription().ifPresent(specialService::setDescription);
        uDto.getOriginCountryId().ifPresent(specialService::setOriginCountryId);
        return save(specialService);
    }

    public ByteArrayResource exportExcel(Specification<SpecialService> specification, Pageable pageable, Map<String, String> alternativeHeaders, Set<String> excludeColumns) {
        Page<SpecialService> collection = find(specification, pageable);
        try {
            InputStream inputStream = GenericExcelGenerator.generateExcelInputStream(collection.toList()
                    , Set.of(), alternativeHeaders, excludeColumns);
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}