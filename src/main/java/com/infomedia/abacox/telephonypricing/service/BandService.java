package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.dto.band.CreateBand;
import com.infomedia.abacox.telephonypricing.dto.band.UpdateBand;
import com.infomedia.abacox.telephonypricing.entity.Band;
import com.infomedia.abacox.telephonypricing.repository.BandRepository;
import com.infomedia.abacox.telephonypricing.service.common.CrudService;
import org.springframework.stereotype.Service;

@Service
public class BandService extends CrudService<Band, Long, BandRepository> {
    public BandService(BandRepository repository) {
        super(repository);
    }


    public Band create(CreateBand cDto){
        Band band = Band.builder()
                .prefixId(cDto.getPrefixId())
                .name(cDto.getName())
                .value(cDto.getValue())
                .vatIncluded(cDto.getVatIncluded())
                .minDistance(cDto.getMinDistance())
                .maxDistance(cDto.getMaxDistance())
                .bandGroupId(cDto.getBandGroupId())
                .build();

        return save(band);
    }

    public Band update(Long id, UpdateBand uDto) {
        Band band = get(id);
        uDto.getPrefixId().ifPresent(band::setPrefixId);
        uDto.getName().ifPresent(band::setName);
        uDto.getValue().ifPresent(band::setValue);
        uDto.getVatIncluded().ifPresent(band::setVatIncluded);
        uDto.getMinDistance().ifPresent(band::setMinDistance);
        uDto.getMaxDistance().ifPresent(band::setMaxDistance);
        uDto.getBandGroupId().ifPresent(band::setBandGroupId);
        return save(band);
    }
}
