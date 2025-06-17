package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.component.export.excel.GenericExcelGenerator;
import com.infomedia.abacox.telephonypricing.dto.specialratevalue.CreateSpecialRateValue;
import com.infomedia.abacox.telephonypricing.dto.specialratevalue.UpdateSpecialRateValue;
import com.infomedia.abacox.telephonypricing.db.entity.SpecialRateValue;
import com.infomedia.abacox.telephonypricing.repository.SpecialRateValueRepository;
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
public class SpecialRateValueService extends CrudService<SpecialRateValue, Long, SpecialRateValueRepository> {
    public SpecialRateValueService(SpecialRateValueRepository repository) {
        super(repository);
    }

    public SpecialRateValue create(CreateSpecialRateValue cDto) {
        SpecialRateValue specialRateValue = SpecialRateValue.builder()
                .name(cDto.getName())
                .rateValue(cDto.getRateValue())
                .includesVat(cDto.getIncludesVat())
                .sundayEnabled(cDto.getSundayEnabled())
                .mondayEnabled(cDto.getMondayEnabled())
                .tuesdayEnabled(cDto.getTuesdayEnabled())
                .wednesdayEnabled(cDto.getWednesdayEnabled())
                .thursdayEnabled(cDto.getThursdayEnabled())
                .fridayEnabled(cDto.getFridayEnabled())
                .saturdayEnabled(cDto.getSaturdayEnabled())
                .holidayEnabled(cDto.getHolidayEnabled())
                .telephonyTypeId(cDto.getTelephonyTypeId())
                .operatorId(cDto.getOperatorId())
                .bandId(cDto.getBandId())
                .validFrom(cDto.getValidFrom())
                .validTo(cDto.getValidTo())
                .originIndicatorId(cDto.getOriginIndicatorId())
                .hoursSpecification(cDto.getHoursSpecification())
                .valueType(cDto.getValueType())
                .build();

        return save(specialRateValue);
    }

    public SpecialRateValue update(Long id, UpdateSpecialRateValue uDto) {
        SpecialRateValue specialRateValue = get(id);
        uDto.getName().ifPresent(specialRateValue::setName);
        uDto.getRateValue().ifPresent(specialRateValue::setRateValue);
        uDto.getIncludesVat().ifPresent(specialRateValue::setIncludesVat);
        uDto.getSundayEnabled().ifPresent(specialRateValue::setSundayEnabled);
        uDto.getMondayEnabled().ifPresent(specialRateValue::setMondayEnabled);
        uDto.getTuesdayEnabled().ifPresent(specialRateValue::setTuesdayEnabled);
        uDto.getWednesdayEnabled().ifPresent(specialRateValue::setWednesdayEnabled);
        uDto.getThursdayEnabled().ifPresent(specialRateValue::setThursdayEnabled);
        uDto.getFridayEnabled().ifPresent(specialRateValue::setFridayEnabled);
        uDto.getSaturdayEnabled().ifPresent(specialRateValue::setSaturdayEnabled);
        uDto.getHolidayEnabled().ifPresent(specialRateValue::setHolidayEnabled);
        uDto.getTelephonyTypeId().ifPresent(specialRateValue::setTelephonyTypeId);
        uDto.getOperatorId().ifPresent(specialRateValue::setOperatorId);
        uDto.getBandId().ifPresent(specialRateValue::setBandId);
        uDto.getValidFrom().ifPresent(specialRateValue::setValidFrom);
        uDto.getValidTo().ifPresent(specialRateValue::setValidTo);
        uDto.getOriginIndicatorId().ifPresent(specialRateValue::setOriginIndicatorId);
        uDto.getHoursSpecification().ifPresent(specialRateValue::setHoursSpecification);
        uDto.getValueType().ifPresent(specialRateValue::setValueType);
        return save(specialRateValue);
    }

    public ByteArrayResource exportExcel(Specification<SpecialRateValue> specification, Pageable pageable, Map<String, String> alternativeHeaders
            , Set<String> excludeColumns, Set<String> includeColumns, Map<String, Map<String, String>> valueReplacements) {
        Page<SpecialRateValue> collection = find(specification, pageable);
        try {
            GenericExcelGenerator.ExcelGeneratorBuilder<?> builder = GenericExcelGenerator.builder(collection.toList());
            if (alternativeHeaders != null) builder.withAlternativeHeaderNames(alternativeHeaders);
            if (excludeColumns != null) builder.withExcludedColumnNames(excludeColumns);
            if (includeColumns != null) builder.withIncludedColumnNames(includeColumns);
            if (valueReplacements != null) builder.withValueReplacements(valueReplacements);
            InputStream inputStream = builder.generateAsInputStream();
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}