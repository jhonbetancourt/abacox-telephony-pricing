package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.component.export.excel.ExcelGeneratorBuilder;
import com.infomedia.abacox.telephonypricing.dto.trunkrate.CreateTrunkRate;
import com.infomedia.abacox.telephonypricing.dto.trunkrate.UpdateTrunkRate;
import com.infomedia.abacox.telephonypricing.db.entity.TrunkRate;
import com.infomedia.abacox.telephonypricing.db.repository.TrunkRateRepository;
import com.infomedia.abacox.telephonypricing.service.common.CrudService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;

@Service
public class TrunkRateService extends CrudService<TrunkRate, Long, TrunkRateRepository> {
    public TrunkRateService(TrunkRateRepository repository) {
        super(repository);
    }

    public TrunkRate create(CreateTrunkRate cDto) {
        TrunkRate trunkRate = TrunkRate.builder()
                .trunkId(cDto.getTrunkId())
                .rateValue(cDto.getRateValue())
                .includesVat(cDto.getIncludesVat())
                .operatorId(cDto.getOperatorId())
                .telephonyTypeId(cDto.getTelephonyTypeId())
                .noPbxPrefix(cDto.getNoPbxPrefix())
                .noPrefix(cDto.getNoPrefix())
                .seconds(cDto.getSeconds())
                .build();

        return save(trunkRate);
    }

    public TrunkRate update(Long id, UpdateTrunkRate uDto) {
        TrunkRate trunkRate = get(id);
        uDto.getTrunkId().ifPresent(trunkRate::setTrunkId);
        uDto.getRateValue().ifPresent(trunkRate::setRateValue);
        uDto.getIncludesVat().ifPresent(trunkRate::setIncludesVat);
        uDto.getOperatorId().ifPresent(trunkRate::setOperatorId);
        uDto.getTelephonyTypeId().ifPresent(trunkRate::setTelephonyTypeId);
        uDto.getNoPbxPrefix().ifPresent(trunkRate::setNoPbxPrefix);
        uDto.getNoPrefix().ifPresent(trunkRate::setNoPrefix);
        uDto.getSeconds().ifPresent(trunkRate::setSeconds);
        return save(trunkRate);
    }

    public ByteArrayResource exportExcel(Specification<TrunkRate> specification, Pageable pageable, ExcelGeneratorBuilder builder) {
        Page<TrunkRate> collection = find(specification, pageable);
       try {
            InputStream inputStream = builder.withEntities(collection.toList()).generateAsInputStream();
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}