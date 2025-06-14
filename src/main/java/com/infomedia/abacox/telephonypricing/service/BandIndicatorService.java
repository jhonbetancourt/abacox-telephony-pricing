package com.infomedia.abacox.telephonypricing.service;


import com.infomedia.abacox.telephonypricing.component.export.excel.GenericExcelGenerator;
import com.infomedia.abacox.telephonypricing.dto.bandindicator.CreateBandIndicator;
import com.infomedia.abacox.telephonypricing.dto.bandindicator.UpdateBandIndicator;
import com.infomedia.abacox.telephonypricing.db.entity.BandIndicator;
import com.infomedia.abacox.telephonypricing.repository.BandIndicatorRepository;
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

    public ByteArrayResource exportExcel(Specification<BandIndicator> specification, Pageable pageable, Map<String, String> alternativeHeaders, Set<String> excludeColumns) {
        Page<BandIndicator> collection = find(specification, pageable);
        try {
            InputStream inputStream = GenericExcelGenerator.generateExcelInputStream(collection.toList()
                    , Set.of(), alternativeHeaders, excludeColumns);
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}