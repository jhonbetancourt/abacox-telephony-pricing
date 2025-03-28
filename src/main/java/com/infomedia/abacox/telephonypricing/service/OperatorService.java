package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.dto.operator.CreateOperator;
import com.infomedia.abacox.telephonypricing.dto.operator.UpdateOperator;
import com.infomedia.abacox.telephonypricing.entity.Operator;
import com.infomedia.abacox.telephonypricing.repository.OperatorRepository;
import com.infomedia.abacox.telephonypricing.service.common.CrudService;
import org.springframework.stereotype.Service;


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
}
