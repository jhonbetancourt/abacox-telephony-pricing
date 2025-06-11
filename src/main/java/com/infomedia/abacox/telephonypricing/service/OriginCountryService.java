package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.component.export.excel.GenericExcelGenerator;
import com.infomedia.abacox.telephonypricing.dto.origincountry.CreateOriginCountry;
import com.infomedia.abacox.telephonypricing.dto.origincountry.UpdateOriginCountry;
import com.infomedia.abacox.telephonypricing.db.entity.OriginCountry;
import com.infomedia.abacox.telephonypricing.repository.OriginCountryRepository;
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

    public ByteArrayResource exportExcel(Specification<OriginCountry> specification, Pageable pageable, Map<String, String> alternativeHeaders, Set<String> excludeColumns) {
        Page<OriginCountry> collection = find(specification, pageable);
        try {
            InputStream inputStream = GenericExcelGenerator.generateExcelInputStream(collection.toList()
                    , Set.of(), alternativeHeaders, excludeColumns);
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}