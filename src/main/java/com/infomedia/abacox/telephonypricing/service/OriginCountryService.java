package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.dto.origincountry.CreateOriginCountry;
import com.infomedia.abacox.telephonypricing.dto.origincountry.UpdateOriginCountry;
import com.infomedia.abacox.telephonypricing.entity.OriginCountry;
import com.infomedia.abacox.telephonypricing.repository.OriginCountryRepository;
import com.infomedia.abacox.telephonypricing.service.common.CrudService;
import org.springframework.stereotype.Service;

@Service
public class OriginCountryService extends CrudService<OriginCountry, Long, OriginCountryRepository> {
    public OriginCountryService(OriginCountryRepository repository) {
        super(repository);
    }

    public OriginCountry create(CreateOriginCountry cDto){
        OriginCountry originCountry = OriginCountry.builder()
                .currencySymbol(cDto.getCurrencySymbol())
                .name(cDto.getName())
                .code(cDto.getCode())
                .build();

        return save(originCountry);
    }

    public OriginCountry update(Long id, UpdateOriginCountry uDto){
        OriginCountry originCountry = get(id);
        uDto.getCurrencySymbol().ifPresent(originCountry::setCurrencySymbol);
        uDto.getName().ifPresent(originCountry::setName);
        uDto.getCode().ifPresent(originCountry::setCode);
        return save(originCountry);
    }
}