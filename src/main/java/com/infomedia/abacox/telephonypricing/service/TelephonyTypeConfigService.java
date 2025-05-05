package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.component.export.excel.GenericExcelGenerator;
import com.infomedia.abacox.telephonypricing.dto.telephonytypeconfig.CreateTelephonyTypeConfig;
import com.infomedia.abacox.telephonypricing.dto.telephonytypeconfig.UpdateTelephonyTypeConfig;
import com.infomedia.abacox.telephonypricing.entity.TelephonyTypeConfig;
import com.infomedia.abacox.telephonypricing.repository.TelephonyTypeConfigRepository;
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
public class TelephonyTypeConfigService extends CrudService<TelephonyTypeConfig, Long, TelephonyTypeConfigRepository> {
    public TelephonyTypeConfigService(TelephonyTypeConfigRepository repository) {
        super(repository);
    }

    public TelephonyTypeConfig create(CreateTelephonyTypeConfig cDto) {
        TelephonyTypeConfig telephonyTypeConfig = TelephonyTypeConfig.builder()
                .minValue(cDto.getMinValue())
                .maxValue(cDto.getMaxValue())
                .telephonyTypeId(cDto.getTelephonyTypeId())
                .originCountryId(cDto.getOriginCountryId())
                .build();

        return save(telephonyTypeConfig);
    }

    public TelephonyTypeConfig update(Long id, UpdateTelephonyTypeConfig uDto) {
        TelephonyTypeConfig telephonyTypeConfig = get(id);
        uDto.getMinValue().ifPresent(telephonyTypeConfig::setMinValue);
        uDto.getMaxValue().ifPresent(telephonyTypeConfig::setMaxValue);
        uDto.getTelephonyTypeId().ifPresent(telephonyTypeConfig::setTelephonyTypeId);
        uDto.getOriginCountryId().ifPresent(telephonyTypeConfig::setOriginCountryId);
        return save(telephonyTypeConfig);
    }

    public ByteArrayResource exportExcel(Specification<TelephonyTypeConfig> specification, Pageable pageable, Map<String, String> alternativeHeaders, Set<String> excludeColumns) {
        Page<TelephonyTypeConfig> collection = find(specification, pageable);
        try {
            InputStream inputStream = GenericExcelGenerator.generateExcelInputStream(collection.toList(),
                    Set.of(), alternativeHeaders, excludeColumns);
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}