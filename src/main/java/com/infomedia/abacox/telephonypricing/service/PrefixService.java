package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.dto.prefix.CreatePrefix;
import com.infomedia.abacox.telephonypricing.dto.prefix.UpdatePrefix;
import com.infomedia.abacox.telephonypricing.entity.Prefix;
import com.infomedia.abacox.telephonypricing.repository.PrefixRepository;
import com.infomedia.abacox.telephonypricing.service.common.CrudService;
import org.springframework.stereotype.Service;

@Service
public class PrefixService extends CrudService<Prefix, Long, PrefixRepository> {
    public PrefixService(PrefixRepository repository) {
        super(repository);
    }

    public Prefix create(CreatePrefix cDto) {
        Prefix prefix = Prefix.builder()
                .operatorId(cDto.getOperatorId())
                .telephoneTypeId(cDto.getTelephoneTypeId())
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
        uDto.getTelephoneTypeId().ifPresent(prefix::setTelephoneTypeId);
        uDto.getCode().ifPresent(prefix::setCode);
        uDto.getBaseValue().ifPresent(prefix::setBaseValue);
        uDto.getBandOk().ifPresent(prefix::setBandOk);
        uDto.getVatIncluded().ifPresent(prefix::setVatIncluded);
        uDto.getVatValue().ifPresent(prefix::setVatValue);
        return save(prefix);
    }
}
