package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.component.export.excel.ExcelGeneratorBuilder;
import com.infomedia.abacox.telephonypricing.dto.series.CreateSeries;
import com.infomedia.abacox.telephonypricing.dto.series.UpdateSeries;
import com.infomedia.abacox.telephonypricing.db.entity.Series;
import com.infomedia.abacox.telephonypricing.repository.SeriesRepository;
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
public class SeriesService extends CrudService<Series, Long, SeriesRepository> {
    public SeriesService(SeriesRepository repository) {
        super(repository);
    }

    public Series create(CreateSeries cDto) {
        Series series = Series.builder()
                .indicatorId(cDto.getIndicatorId())
                .ndc(cDto.getNdc())
                .initialNumber(cDto.getInitialNumber())
                .finalNumber(cDto.getFinalNumber())
                .company(cDto.getCompany())
                .build();

        return save(series);
    }

    public Series update(Long id, UpdateSeries uDto) {
        Series series = get(id);
        uDto.getIndicatorId().ifPresent(series::setIndicatorId);
        uDto.getNdc().ifPresent(series::setNdc);
        uDto.getInitialNumber().ifPresent(series::setInitialNumber);
        uDto.getFinalNumber().ifPresent(series::setFinalNumber);
        uDto.getCompany().ifPresent(series::setCompany);

        return save(series);
    }

    public ByteArrayResource exportExcel(Specification<Series> specification, Pageable pageable, ExcelGeneratorBuilder builder) {
        Page<Series> collection = find(specification, pageable);
       try {
            InputStream inputStream = builder.withEntities(collection.toList()).generateAsInputStream();
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}