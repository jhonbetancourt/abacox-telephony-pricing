package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.component.csv.CsvReader;
import com.infomedia.abacox.telephonypricing.component.utils.JpaUtils;
import com.infomedia.abacox.telephonypricing.dto.jobposition.JobPositionLegacyMapping;
import com.infomedia.abacox.telephonypricing.entity.JobPosition;
import com.infomedia.abacox.telephonypricing.repository.JobPositionRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Service
@RequiredArgsConstructor
@Log4j2
public class LegacyDataLoadingService {

    private final JobPositionRepository jobPositionRepository;
    private final EntityManager entityManager;
    private final DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    public void loadJobPositionData(InputStream csvInputStream, JobPositionLegacyMapping legacyMapping){
        try (CsvReader csvReader = new CsvReader(csvInputStream, ",", true)) {
            csvReader.processRowsAsMap(csvRow -> {
                Long id = Long.parseLong(csvRow.get(legacyMapping.getId()));
                if(!jobPositionRepository.existsById(id)){
                    String name = csvRow.get(legacyMapping.getName());
                    LocalDateTime createdDate = parseDateTime(csvRow.get(legacyMapping.getCreatedDate()));
                    String createdBy = csvRow.get(legacyMapping.getCreatedBy());
                    LocalDateTime lastModifiedDate = parseDateTime(csvRow.get(legacyMapping.getLastModifiedDate()));
                    String lastModifiedBy = csvRow.get(legacyMapping.getLastModifiedBy());
                    JobPosition jobPosition = JobPosition.builder()
                            .id(id)
                            .name(name)
                            .createdDate(createdDate)
                            .createdBy(createdBy)
                            .lastModifiedDate(lastModifiedDate)
                            .lastModifiedBy(lastModifiedBy)
                            .build();
                    try {
                        JpaUtils.saveEntityWithForcedId(jobPosition, entityManager);
                    }catch (Exception e){
                        log.error("Failed to save job position data: {}", jobPosition);
                    }
                }
            });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private LocalDateTime parseDateTime(String dateString) {
        try {
            return LocalDateTime.parse(dateString, dateTimeFormatter);
        } catch (Exception e) {
            return null;
        }
    }
}
