package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.component.export.excel.GenericExcelGenerator;
import com.infomedia.abacox.telephonypricing.dto.indicator.CreateIndicator;
import com.infomedia.abacox.telephonypricing.dto.indicator.UpdateIndicator;
import com.infomedia.abacox.telephonypricing.entity.Indicator;
import com.infomedia.abacox.telephonypricing.repository.IndicatorRepository;
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
public class IndicatorService extends CrudService<Indicator, Long, IndicatorRepository>{
    public IndicatorService(IndicatorRepository repository) {
        super(repository);
    }

    public Indicator create(CreateIndicator cDto){
        Indicator indicator = Indicator.builder()
                .telephonyTypeId(cDto.getTelephonyTypeId())
                .departmentCountry(cDto.getDepartmentCountry())
                .cityName(cDto.getCityName())
                .cityId(cDto.getCityId())
                .isAssociated(cDto.isAssociated())
                .operatorId(cDto.getOperatorId())
                .originCountryId(cDto.getOriginCountryId())
                .build();

        return save(indicator);
    }

    public Indicator update(Long id, UpdateIndicator uDto){
        Indicator indicator = get(id);
        uDto.getTelephonyTypeId().ifPresent(indicator::setTelephonyTypeId);
        uDto.getDepartmentCountry().ifPresent(indicator::setDepartmentCountry);
        uDto.getCityName().ifPresent(indicator::setCityName);
        uDto.getCityId().ifPresent(indicator::setCityId);
        uDto.getIsAssociated().ifPresent(indicator::setAssociated);
        uDto.getOperatorId().ifPresent(indicator::setOperatorId);
        uDto.getOriginCountryId().ifPresent(indicator::setOriginCountryId);
        return save(indicator);
    }

    public ByteArrayResource exportExcel(Specification<Indicator> specification, Pageable pageable, Map<String, String> alternativeHeaders, Set<String> excludeColumns) {
        Page<Indicator> collection = find(specification, pageable);
        try {
            InputStream inputStream = GenericExcelGenerator.generateExcelInputStream(collection.toList()
                    , Set.of(), alternativeHeaders, excludeColumns);
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}