package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.dto.indicator.CreateIndicator;
import com.infomedia.abacox.telephonypricing.dto.indicator.UpdateIndicator;
import com.infomedia.abacox.telephonypricing.entity.Indicator;
import com.infomedia.abacox.telephonypricing.repository.IndicatorRepository;
import com.infomedia.abacox.telephonypricing.service.common.CrudService;
import org.springframework.stereotype.Service;

@Service
public class IndicatorService extends CrudService<Indicator, Long, IndicatorRepository>{
    public IndicatorService(IndicatorRepository repository) {
        super(repository);
    }

    public Indicator create(CreateIndicator cDto){
        Indicator indicator = Indicator.builder()
                .telephonyTypeId(cDto.getTelephonyTypeId())
                .code(cDto.getCode())
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
        uDto.getCode().ifPresent(indicator::setCode);
        uDto.getDepartmentCountry().ifPresent(indicator::setDepartmentCountry);
        uDto.getCityName().ifPresent(indicator::setCityName);
        uDto.getCityId().ifPresent(indicator::setCityId);
        uDto.getIsAssociated().ifPresent(indicator::setAssociated);
        uDto.getOperatorId().ifPresent(indicator::setOperatorId);
        uDto.getOriginCountryId().ifPresent(indicator::setOriginCountryId);
        return save(indicator);
    }
}
