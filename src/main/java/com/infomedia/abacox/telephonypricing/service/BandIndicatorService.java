package com.infomedia.abacox.telephonypricing.service;


import com.infomedia.abacox.telephonypricing.dto.bandindicator.CreateBandIndicator;
import com.infomedia.abacox.telephonypricing.dto.bandindicator.UpdateBandIndicator;
import com.infomedia.abacox.telephonypricing.entity.BandIndicator;
import com.infomedia.abacox.telephonypricing.repository.BandIndicatorRepository;
import com.infomedia.abacox.telephonypricing.service.common.CrudService;
import org.springframework.stereotype.Service;

@Service
public class BandIndicatorService extends CrudService<BandIndicator, Long, BandIndicatorRepository> {
    public BandIndicatorService(BandIndicatorRepository repository) {
        super(repository);
    }

    public BandIndicator create(CreateBandIndicator cDto) {
        BandIndicator bandIndicator = BandIndicator.builder()
                .bandId(cDto.getBandId())
                .indicatorId(cDto.getIndicatorId())
                .build();
        return save(bandIndicator);
    }

    public BandIndicator update(Long id, UpdateBandIndicator uDto) {
        BandIndicator bandIndicator = get(id);
        uDto.getBandId().ifPresent(bandIndicator::setBandId);
        uDto.getIndicatorId().ifPresent(bandIndicator::setIndicatorId);
        return save(bandIndicator);
    }
}
