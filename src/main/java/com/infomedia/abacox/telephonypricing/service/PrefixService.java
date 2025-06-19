package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.component.export.excel.ExcelGeneratorBuilder;
import com.infomedia.abacox.telephonypricing.dto.prefix.CreatePrefix;
import com.infomedia.abacox.telephonypricing.dto.prefix.UpdatePrefix;
import com.infomedia.abacox.telephonypricing.db.entity.Prefix;
import com.infomedia.abacox.telephonypricing.repository.PrefixRepository;
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
public class PrefixService extends CrudService<Prefix, Long, PrefixRepository> {
    public PrefixService(PrefixRepository repository) {
        super(repository);
    }

    public Prefix create(CreatePrefix cDto) {
        Prefix prefix = Prefix.builder()
                .operatorId(cDto.getOperatorId())
                .telephonyTypeId(cDto.getTelephonyTypeId())
                .code(cDto.getCode())
                .baseValue(cDto.getBaseValue())
                .bandOk(cDto.getBandOk())
                .vatIncluded(cDto.getVatIncluded())
                .vatValue(cDto.getVatValue())
                .build();

        return save(prefix);
    }

    public Prefix update(Long id, UpdatePrefix uDto) {
        Prefix prefix = get(id);
        uDto.getOperatorId().ifPresent(prefix::setOperatorId);
        uDto.getTelephonyTypeId().ifPresent(prefix::setTelephonyTypeId);
        uDto.getCode().ifPresent(prefix::setCode);
        uDto.getBaseValue().ifPresent(prefix::setBaseValue);
        uDto.getBandOk().ifPresent(prefix::setBandOk);
        uDto.getVatIncluded().ifPresent(prefix::setVatIncluded);
        uDto.getVatValue().ifPresent(prefix::setVatValue);
        return save(prefix);
    }

    public ByteArrayResource exportExcel(Specification<Prefix> specification, Pageable pageable, ExcelGeneratorBuilder builder) {
        Page<Prefix> collection = find(specification, pageable);
       try {
            InputStream inputStream = builder.withEntities(collection.toList()).generateAsInputStream();
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}