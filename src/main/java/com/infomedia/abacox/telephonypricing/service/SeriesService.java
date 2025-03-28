package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.dto.series.CreateSeries;
import com.infomedia.abacox.telephonypricing.dto.series.UpdateSeries;
import com.infomedia.abacox.telephonypricing.entity.Series;
import com.infomedia.abacox.telephonypricing.repository.SeriesRepository;
import com.infomedia.abacox.telephonypricing.service.common.CrudService;
import org.springframework.stereotype.Service;

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
}
