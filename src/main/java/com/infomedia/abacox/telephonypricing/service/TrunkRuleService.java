package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.component.export.excel.ExcelGeneratorBuilder;
import com.infomedia.abacox.telephonypricing.dto.trunkrule.CreateTrunkRule;
import com.infomedia.abacox.telephonypricing.dto.trunkrule.UpdateTrunkRule;
import com.infomedia.abacox.telephonypricing.db.entity.TrunkRule;
import com.infomedia.abacox.telephonypricing.repository.TrunkRuleRepository;
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
public class TrunkRuleService extends CrudService<TrunkRule, Long, TrunkRuleRepository> {
    public TrunkRuleService(TrunkRuleRepository repository) {
        super(repository);
    }

    public TrunkRule create(CreateTrunkRule cDto) {
        TrunkRule trunkRule = TrunkRule.builder()
                .rateValue(cDto.getRateValue())
                .includesVat(cDto.getIncludesVat())
                .telephonyTypeId(cDto.getTelephonyTypeId())
                .indicatorIds(cDto.getIndicatorIds())
                .trunkId(cDto.getTrunkId())
                .newOperatorId(cDto.getNewOperatorId())
                .newTelephonyTypeId(cDto.getNewTelephonyTypeId())
                .seconds(cDto.getSeconds())
                .originIndicatorId(cDto.getOriginIndicatorId())
                .build();

        return save(trunkRule);
    }

    public TrunkRule update(Long id, UpdateTrunkRule uDto) {
        TrunkRule trunkRule = get(id);
        uDto.getRateValue().ifPresent(trunkRule::setRateValue);
        uDto.getIncludesVat().ifPresent(trunkRule::setIncludesVat);
        uDto.getTelephonyTypeId().ifPresent(trunkRule::setTelephonyTypeId);
        uDto.getIndicatorIds().ifPresent(trunkRule::setIndicatorIds);
        uDto.getTrunkId().ifPresent(trunkRule::setTrunkId);
        uDto.getNewOperatorId().ifPresent(trunkRule::setNewOperatorId);
        uDto.getNewTelephonyTypeId().ifPresent(trunkRule::setNewTelephonyTypeId);
        uDto.getSeconds().ifPresent(trunkRule::setSeconds);
        uDto.getOriginIndicatorId().ifPresent(trunkRule::setOriginIndicatorId);
        return save(trunkRule);
    }

    public ByteArrayResource exportExcel(Specification<TrunkRule> specification, Pageable pageable, ExcelGeneratorBuilder builder) {
        Page<TrunkRule> collection = find(specification, pageable);
       try {
            InputStream inputStream = builder.withEntities(collection.toList()).generateAsInputStream();
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}