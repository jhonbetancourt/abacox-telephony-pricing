package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.component.export.excel.ExcelGeneratorBuilder;
import com.infomedia.abacox.telephonypricing.dto.band.CreateBand;
import com.infomedia.abacox.telephonypricing.dto.band.UpdateBand;
import com.infomedia.abacox.telephonypricing.db.entity.Band;
import com.infomedia.abacox.telephonypricing.repository.BandRepository;
import com.infomedia.abacox.telephonypricing.service.common.CrudService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;

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
                .originIndicatorId(cDto.getOriginIndicatorId())
                .vatIncluded(cDto.getVatIncluded())
                .reference(cDto.getReference())
                .build();

        return save(band);
    }

    public Band update(Long id, UpdateBand uDto) {
        Band band = get(id);
        uDto.getPrefixId().ifPresent(band::setPrefixId);
        uDto.getName().ifPresent(band::setName);
        uDto.getValue().ifPresent(band::setValue);
        uDto.getOriginIndicatorId().ifPresent(band::setOriginIndicatorId);
        uDto.getVatIncluded().ifPresent(band::setVatIncluded);
        uDto.getReference().ifPresent(band::setReference);
        return save(band);
    }

    public ByteArrayResource exportExcel(Specification<Band> specification, Pageable pageable, ExcelGeneratorBuilder builder) {
        Page<Band> collection = find(specification, pageable);
        try {
            InputStream inputStream = builder.withEntities(collection.toList()).generateAsInputStream();
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}