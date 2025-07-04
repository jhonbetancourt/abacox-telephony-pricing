package com.infomedia.abacox.telephonypricing.service;


import com.infomedia.abacox.telephonypricing.component.export.excel.ExcelGeneratorBuilder;
import com.infomedia.abacox.telephonypricing.dto.bandindicator.CreateBandIndicator;
import com.infomedia.abacox.telephonypricing.dto.bandindicator.UpdateBandIndicator;
import com.infomedia.abacox.telephonypricing.db.entity.BandIndicator;
import com.infomedia.abacox.telephonypricing.db.repository.BandIndicatorRepository;
import com.infomedia.abacox.telephonypricing.service.common.CrudService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;

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

    public ByteArrayResource exportExcel(Specification<BandIndicator> specification, Pageable pageable, ExcelGeneratorBuilder builder) {
        Page<BandIndicator> collection = find(specification, pageable);
       try {
            InputStream inputStream = builder.withEntities(collection.toList()).generateAsInputStream();
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}