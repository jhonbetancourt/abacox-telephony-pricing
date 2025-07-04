package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.component.csv.CsvToDatabaseLoader;
import com.infomedia.abacox.telephonypricing.db.entity.*;
import com.infomedia.abacox.telephonypricing.db.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Log4j2
public class DefaultDataLoadingService {

    private final CsvToDatabaseLoader csvToDatabaseLoader;
    private final TelephonyTypeRepository telephonyTypeRepository;
    private final CityRepository cityRepository;
    private final OperatorRepository operatorRepository;
    private final IndicatorRepository indicatorRepository;
    private final OriginCountryRepository originCountryRepository;
    private final CallCategoryRepository callCategoryRepository;
    private final PrefixRepository prefixRepository;
    private final SeriesRepository seriesRepository;


    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        loadOriginCountry();
        loadCallCategory();
        loadTelephonyType();
        loadOperator();
        loadCity();
        loadPrefix();
        loadIndicator();
        loadSeries();
    }

    private void loadTelephonyType() {
        if (telephonyTypeRepository.count() == 0) {
            log.info("Loading telephony types");
            ClassPathResource bandIndicatorCsv = new ClassPathResource("csv/telephony_type.csv");
            try {
                csvToDatabaseLoader.loadFromInputStreamForceIds(bandIndicatorCsv.getInputStream(), TelephonyType.class);
            } catch (Exception e) {
                log.error("Error loading telephony type data into database", e);
            }
        }
    }

    private void loadCity() {
        if (cityRepository.count() == 0) {
            log.info("Loading cities");
            ClassPathResource cityCsv = new ClassPathResource("csv/city.csv");
            try {
                csvToDatabaseLoader.loadFromInputStreamForceIds(cityCsv.getInputStream(), City.class);
            } catch (Exception e) {
                log.error("Error loading city data into database", e);
            }
        }
    }

    private void loadOperator() {
        if (operatorRepository.count() == 0) {
            log.info("Loading operators");
            ClassPathResource operatorCsv = new ClassPathResource("csv/operator.csv");
            try {
                csvToDatabaseLoader.loadFromInputStreamForceIds(operatorCsv.getInputStream(), Operator.class);
            } catch (Exception e) {
                log.error("Error loading operator data into database", e);
            }
        }
    }

    private void loadIndicator() {
        if (indicatorRepository.count() == 0) {
            log.info("Loading indicators");
            ClassPathResource indicatorCsv = new ClassPathResource("csv/indicator.csv");
            try {
                csvToDatabaseLoader.loadFromInputStreamForceIds(indicatorCsv.getInputStream(), Indicator.class);
            } catch (Exception e) {
                log.error("Error loading indicator data into database", e);
            }
        }
    }

    private void loadOriginCountry() {
        if (originCountryRepository.count() == 0) {
            log.info("Loading origin countries");
            ClassPathResource originCountryCsv = new ClassPathResource("csv/origin_country.csv");
            try {
                csvToDatabaseLoader.loadFromInputStreamForceIds(originCountryCsv.getInputStream(), OriginCountry.class);
            } catch (Exception e) {
                log.error("Error loading origin country data into database", e);
            }
        }
    }

    private void loadCallCategory() {
        if (callCategoryRepository.count() == 0) {
            log.info("Loading call categories");
            ClassPathResource callCategoryCsv = new ClassPathResource("csv/call_category.csv");
            try {
                csvToDatabaseLoader.loadFromInputStreamForceIds(callCategoryCsv.getInputStream(), CallCategory.class);
            } catch (Exception e) {
                log.error("Error loading call category data into database", e);
            }
        }
    }

    private void loadPrefix() {
        if (prefixRepository.count() == 0) {
            log.info("Loading prefixes");
            ClassPathResource prefixCsv = new ClassPathResource("csv/prefix.csv");
            try {
                csvToDatabaseLoader.loadFromInputStreamForceIds(prefixCsv.getInputStream(), Prefix.class);
            } catch (Exception e) {
                log.error("Error loading prefix data into database", e);
            }
        }
    }

    private void loadSeries() {
        if (seriesRepository.count() == 0) {
            log.info("Loading series");
            ClassPathResource seriesCsv = new ClassPathResource("csv/series.csv");
            try {
                csvToDatabaseLoader.loadFromInputStreamForceIds(seriesCsv.getInputStream(), Series.class);
            } catch (Exception e) {
                log.error("Error loading series data into database", e);
            }
        }
    }
}
