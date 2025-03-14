package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.component.csv.CsvReader;
import com.infomedia.abacox.telephonypricing.component.utils.JpaUtils;
import com.infomedia.abacox.telephonypricing.dto.costcenter.CostCenterLegacyMapping;
import com.infomedia.abacox.telephonypricing.dto.subdivision.SubdivisionLegacyMapping;
import com.infomedia.abacox.telephonypricing.entity.CostCenter;
import com.infomedia.abacox.telephonypricing.entity.JobPosition;
import com.infomedia.abacox.telephonypricing.entity.Subdivision;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Log4j2
public class LegacyDataLoadingService {
    private final EntityManager entityManager;
    private final DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    public void loadJobPositionData(InputStream csvInputStream, SubdivisionLegacyMapping legacyMapping){
        try (CsvReader csvReader = new CsvReader(csvInputStream, ",", true)) {
            csvReader.processRowsAsMap(csvRow -> {
                Long id = Long.parseLong(csvRow.get(legacyMapping.getId()));
                if(!JpaUtils.entityExists(JobPosition.class, id, entityManager)){
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

    public void loadCostCenterData(InputStream csvInputStream, CostCenterLegacyMapping legacyMapping) {
        try (CsvReader csvReader = new CsvReader(csvInputStream, ",", true)) {
            Map<Long, CsvReader.CsvRow> costCenterMap = csvReader.readAllRows()
                    .stream().filter(csvRow -> !JpaUtils.entityExists(CostCenter.class
                            , Long.parseLong(csvRow.get(legacyMapping.getId())), entityManager))
                    .collect(Collectors.toMap(csvRow -> Long.parseLong(csvRow.get(legacyMapping.getId())), Function.identity()));

            Set<Long> processedIds = new HashSet<>();
            costCenterMap.forEach((id, csvRow) -> {
                // Skip if already processed
                if (processedIds.contains(id)) {
                    return;
                }

                String name = csvRow.get(legacyMapping.getName());
                String workOrder = csvRow.get(legacyMapping.getWorkOrder());
                Long parentCostCenterId = parseLongId(csvRow.get(legacyMapping.getParentCostCenterId()));
                Long originCountryId = parseLongId(csvRow.get(legacyMapping.getOriginCountryId()));

                CostCenter costCenter = CostCenter.builder()
                        .id(id)
                        .name(name)
                        .originCountryId(originCountryId)
                        .workOrder(workOrder)
                        .parentCostCenterId(parentCostCenterId)
                        .build();

                insertWithParentRecursively(
                        costCenter,
                        CostCenter::getId,
                        CostCenter::getParentCostCenterId,
                        parentId -> costCenterMap.containsKey(parentId) ?
                                CostCenter.builder()
                                        .id(parentId)
                                        .name(costCenterMap.get(parentId).get(legacyMapping.getName()))
                                        .originCountryId(parseLongId(costCenterMap.get(parentId).get(legacyMapping.getOriginCountryId())))
                                        .workOrder(costCenterMap.get(parentId).get(legacyMapping.getWorkOrder()))
                                        .parentCostCenterId(parseLongId(costCenterMap.get(parentId).get(legacyMapping.getParentCostCenterId())))
                                        .build()
                                : null,
                        CostCenter.class,
                        processedIds
                );
            });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void loadSubdivisionData(InputStream csvInputStream, SubdivisionLegacyMapping legacyMapping) {
        try (CsvReader csvReader = new CsvReader(csvInputStream, ",", true)) {
            Map<Long, CsvReader.CsvRow> subdivisionMap = csvReader.readAllRows()
                    .stream().filter(csvRow -> !JpaUtils.entityExists(CostCenter.class
                            , Long.parseLong(csvRow.get(legacyMapping.getId())), entityManager))
                    .collect(Collectors.toMap(csvRow -> Long.parseLong(csvRow.get(legacyMapping.getId())), Function.identity()));

            Set<Long> processedIds = new HashSet<>();
            subdivisionMap.forEach((id, csvRow) -> {
                // Skip if already processed
                if (processedIds.contains(id)) {
                    return;
                }

                String name = csvRow.get(legacyMapping.getName());
                Long parentSubdivisionId = parseLongId(csvRow.get(legacyMapping.getParentSubdivisionId()));

                Subdivision subdivision = Subdivision.builder()
                        .id(id)
                        .name(name)
                        .parentSubdivisionId(parentSubdivisionId)
                        .build();

                insertWithParentRecursively(
                        subdivision,
                        Subdivision::getId,
                        Subdivision::getParentSubdivisionId,
                        parentId -> subdivisionMap.containsKey(parentId) ?
                                Subdivision.builder()
                                        .id(parentId)
                                        .name(subdivisionMap.get(parentId).get(legacyMapping.getName()))
                                        .parentSubdivisionId(parseLongId(subdivisionMap.get(parentId).get(legacyMapping.getParentSubdivisionId())))
                                        .build()
                                : null,
                        Subdivision.class,
                        processedIds
                );
            });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Long parseLongId(String idString) {
        try {
            long id = Long.parseLong(idString);
            return id > 0 ? id : null;
        } catch (Exception e) {
            return null;
        }
    }

    private LocalDateTime parseDateTime(String dateString) {
        try {
            return LocalDateTime.parse(dateString, dateTimeFormatter);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Recursively inserts an entity and its parent if the parent doesn't exist.
     * @param <T> Entity type
     * @param <ID> ID type
     * @param entity Entity to insert
     * @param idGetter Function to get ID from entity
     * @param parentIdGetter Function to get parent ID from entity
     * @param parentGetter Function to get parent entity by ID
     * @param entityClass Class of the entity
     * @param processedIds Set of already processed IDs to avoid duplicates
     */
    private <T, ID> void insertWithParentRecursively(
            T entity,
            java.util.function.Function<T, ID> idGetter,
            java.util.function.Function<T, ID> parentIdGetter,
            java.util.function.Function<ID, T> parentGetter,
            Class<T> entityClass,
            Set<ID> processedIds) {

        ID id = idGetter.apply(entity);

        // Skip if this ID has already been processed or exists in the database
        if (processedIds.contains(id) || JpaUtils.entityExists(entityClass, id, entityManager)) {
            return;
        }

        // Mark this ID as being processed
        processedIds.add(id);

        ID parentId = parentIdGetter.apply(entity);

        if (parentId != null && !JpaUtils.entityExists(entityClass, parentId, entityManager) && !processedIds.contains(parentId)) {
            // Parent doesn't exist - fetch and insert it first
            T parentEntity = parentGetter.apply(parentId);
            if (parentEntity != null) {
                insertWithParentRecursively(parentEntity, idGetter, parentIdGetter, parentGetter, entityClass, processedIds);
            } else {
                log.warn("Parent entity with ID {} for {} not found", parentId, entity);
            }
        }

        try {
            JpaUtils.saveEntityWithForcedId(entity, entityManager);
            log.debug("Successfully saved entity: {}", entity);
        } catch (Exception e) {
            log.error("Failed to save entity: {}", entity, e);
        }
    }
}
