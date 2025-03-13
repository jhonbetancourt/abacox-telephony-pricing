package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.dto.bandgroup.CreateBandGroup;
import com.infomedia.abacox.telephonypricing.dto.bandgroup.UpdateBandGroup;
import com.infomedia.abacox.telephonypricing.entity.BandGroup;
import com.infomedia.abacox.telephonypricing.repository.BandGroupRepository;
import com.infomedia.abacox.telephonypricing.service.common.CrudService;
import org.springframework.stereotype.Service;

@Service
public class BandGroupService extends CrudService<BandGroup, Long, BandGroupRepository> {
    public BandGroupService(BandGroupRepository repository) {
        super(repository);
    }

    public BandGroup create(CreateBandGroup cDto) {
        BandGroup bandGroup = BandGroup
                .builder()
                .name(cDto.getName())
                .build();
        return save(bandGroup);
    }

    public BandGroup update(Long id, UpdateBandGroup uDto) {
        BandGroup bandGroup = get(id);
        uDto.getName().ifPresent(bandGroup::setName);
        return save(bandGroup);
    }
}
