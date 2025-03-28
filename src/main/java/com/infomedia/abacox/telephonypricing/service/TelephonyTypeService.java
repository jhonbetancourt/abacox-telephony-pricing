package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.dto.telephonytype.CreateTelephonyType;
import com.infomedia.abacox.telephonypricing.dto.telephonytype.UpdateTelephonyType;
import com.infomedia.abacox.telephonypricing.entity.TelephonyType;
import com.infomedia.abacox.telephonypricing.repository.TelephonyTypeRepository;
import com.infomedia.abacox.telephonypricing.service.common.CrudService;
import org.springframework.stereotype.Service;

@Service
public class TelephonyTypeService extends CrudService<TelephonyType, Long, TelephonyTypeRepository> {
    public TelephonyTypeService(TelephonyTypeRepository repository) {
        super(repository);
    }

    public TelephonyType create(CreateTelephonyType cDto) {
        TelephonyType telephonyType = TelephonyType.builder()
                .name(cDto.getName())
                .callCategoryId(cDto.getCallCategoryId())
                .usesTrunks(cDto.getUsesTrunks())
                .build();
        return save(telephonyType);
    }

    public TelephonyType update(Long id, UpdateTelephonyType uDto) {
        TelephonyType telephonyType = get(id);
        uDto.getName().ifPresent(telephonyType::setName);
        uDto.getCallCategoryId().ifPresent(telephonyType::setCallCategoryId);
        uDto.getUsesTrunks().ifPresent(telephonyType::setUsesTrunks);
        return save(telephonyType);
    }
}
