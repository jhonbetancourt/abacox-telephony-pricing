package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.component.export.excel.GenericExcelGenerator;
import com.infomedia.abacox.telephonypricing.dto.operator.CreateOperator;
import com.infomedia.abacox.telephonypricing.dto.operator.UpdateOperator;
import com.infomedia.abacox.telephonypricing.db.entity.Operator;
import com.infomedia.abacox.telephonypricing.repository.OperatorRepository;
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
public class OperatorService extends CrudService<Operator, Long, OperatorRepository> {
    public OperatorService(OperatorRepository repository) {
        super(repository);
    }

    public Operator create(CreateOperator cDto) {
        Operator operator = Operator.builder()
                .name(cDto.getName())
                .originCountryId(cDto.getOriginCountryId())
                .build();
        return save(operator);
    }

    public Operator update(Long id, UpdateOperator uDto) {
        Operator operator = get(id);
        uDto.getName().ifPresent(operator::setName);
        uDto.getOriginCountryId().ifPresent(operator::setOriginCountryId);
        return save(operator);
    }

    public ByteArrayResource exportExcel(Specification<Operator> specification, Pageable pageable, Map<String, String> alternativeHeaders
            , Set<String> excludeColumns, Set<String> includeColumns, Map<String, Map<String, String>> valueReplacements) {
        Page<Operator> collection = find(specification, pageable);
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