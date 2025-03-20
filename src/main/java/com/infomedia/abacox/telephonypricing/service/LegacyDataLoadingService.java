package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.component.csv.CsvReader;
import com.infomedia.abacox.telephonypricing.component.utils.JpaUtils;
import com.infomedia.abacox.telephonypricing.dto.callrecord.CallRecordLegacyMapping;
import com.infomedia.abacox.telephonypricing.dto.commlocation.CommLocationLegacyMapping;
import com.infomedia.abacox.telephonypricing.dto.costcenter.CostCenterLegacyMapping;
import com.infomedia.abacox.telephonypricing.dto.employee.EmployeeLegacyMapping;
import com.infomedia.abacox.telephonypricing.dto.indicator.IndicatorLegacyMapping;
import com.infomedia.abacox.telephonypricing.dto.jobposition.JobPositionLegacyMapping;
import com.infomedia.abacox.telephonypricing.dto.operator.OperatorLegacyMapping;
import com.infomedia.abacox.telephonypricing.dto.planttype.PlantTypeLegacyMapping;
import com.infomedia.abacox.telephonypricing.dto.subdivision.SubdivisionLegacyMapping;
import com.infomedia.abacox.telephonypricing.dto.telephonytype.TelephonyTypeLegacyMapping;
import com.infomedia.abacox.telephonypricing.entity.*;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
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

    public void loadIndicatorData(InputStream csvInputStream, IndicatorLegacyMapping legacyMapping) {
        try (CsvReader csvReader = new CsvReader(csvInputStream, ",", true)) {
            csvReader.processRowsAsMap(csvRow -> {
                Long id = Long.parseLong(csvRow.get(legacyMapping.getId()));
                if(!JpaUtils.entityExists(Indicator.class, id, entityManager)){
                    String departmentCountry = csvRow.get(legacyMapping.getDepartmentCountry());
                    Long cityId = parseLongId(csvRow.get(legacyMapping.getCityId()));
                    String cityName = csvRow.get(legacyMapping.getCityName());
                    Boolean isAssociated = parseBoolean(csvRow.get(legacyMapping.getIsAssociated()));
                    Long operatorId = parseLongId(csvRow.get(legacyMapping.getOperatorId()));
                    Long originCountryId = parseLongId(csvRow.get(legacyMapping.getOriginCountryId()));
                    Long telephonyTypeId = parseLongId(csvRow.get(legacyMapping.getTelephonyTypeId()));
                    Indicator indicator = Indicator.builder()
                            .id(id)
                            .telephonyTypeId(telephonyTypeId)
                            .departmentCountry(departmentCountry)
                            .cityId(cityId)
                            .cityName(cityName)
                            .isAssociated(isAssociated)
                            .operatorId(operatorId)
                            .originCountryId(originCountryId)
                            .telephonyTypeId(telephonyTypeId)
                            .build();
                    try {
                        JpaUtils.saveEntityWithForcedId(indicator, entityManager);
                    }catch (Exception e){
                        log.error("Failed to save indicator data: {}", indicator);
                    }
                }
            });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void loadTelephonyTypeData(InputStream csvInputStream, TelephonyTypeLegacyMapping legacyMapping) {
        try (CsvReader csvReader = new CsvReader(csvInputStream, ",", true)) {
            csvReader.processRowsAsMap(csvRow -> {
                Long id = Long.parseLong(csvRow.get(legacyMapping.getId()));
                if(!JpaUtils.entityExists(TelephonyType.class, id, entityManager)){
                    String name = csvRow.get(legacyMapping.getName());
                    Long callCategoryId = parseLongId(csvRow.get(legacyMapping.getCallCategoryId()));
                    Boolean usesTrunks = parseBoolean(csvRow.get(legacyMapping.getUsesTrunks()));
                    TelephonyType telephonyType = TelephonyType.builder()
                            .id(id)
                            .name(name)
                            .callCategoryId(callCategoryId)
                            .usesTrunks(usesTrunks)
                            .build();
                    try {
                        JpaUtils.saveEntityWithForcedId(telephonyType, entityManager);
                    }catch (Exception e){
                        log.error("Failed to save telephony type data: {}", telephonyType);
                    }
                }
            });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void loadOperatorData(InputStream csvInputStream, OperatorLegacyMapping legacyMapping) {
        try (CsvReader csvReader = new CsvReader(csvInputStream, ",", true)) {
            csvReader.processRowsAsMap(csvRow -> {
                Long id = Long.parseLong(csvRow.get(legacyMapping.getId()));
                if(!JpaUtils.entityExists(Operator.class, id, entityManager)){
                    String name = csvRow.get(legacyMapping.getName());
                    Long originCountryId = parseLongId(csvRow.get(legacyMapping.getOriginCountryId()));
                    Operator operator = Operator.builder()
                            .id(id)
                            .name(name)
                            .originCountryId(originCountryId)
                            .build();
                    try {
                        JpaUtils.saveEntityWithForcedId(operator, entityManager);
                    }catch (Exception e){
                        log.error("Failed to save operator data: {}", operator);
                    }
                }
            });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void loadCallRecordData(InputStream csvInputStream, CallRecordLegacyMapping legacyMapping){
        try (CsvReader csvReader = new CsvReader(csvInputStream, ",", true)) {
            csvReader.processRowsAsMap(csvRow -> {
                Long id = Long.parseLong(csvRow.get(legacyMapping.getId()));
                if(!JpaUtils.entityExists(CallRecord.class, id, entityManager)){
                    String dial = csvRow.get(legacyMapping.getDial());
                    Long commLocationId = parseLongId(csvRow.get(legacyMapping.getCommLocationId()));
                    LocalDateTime serviceDate = parseDateTime(csvRow.get(legacyMapping.getServiceDate()));
                    Long operatorId = parseLongId(csvRow.get(legacyMapping.getOperatorId()));
                    String employeeExtension = csvRow.get(legacyMapping.getEmployeeExtension());
                    String employeeKey = csvRow.get(legacyMapping.getEmployeeKey());
                    Long indicatorId = parseLongId(csvRow.get(legacyMapping.getIndicatorId()));
                    String destinationPhone = csvRow.get(legacyMapping.getDestinationPhone());
                    Integer duration = Integer.parseInt(csvRow.get(legacyMapping.getDuration()));
                    Integer ringCount = Integer.parseInt(csvRow.get(legacyMapping.getRingCount()));
                    Long telephonyTypeId = parseLongId(csvRow.get(legacyMapping.getTelephonyTypeId()));
                    BigDecimal billedAmount = new BigDecimal(csvRow.get(legacyMapping.getBilledAmount()));
                    BigDecimal pricePerMinute = new BigDecimal(csvRow.get(legacyMapping.getPricePerMinute()));
                    BigDecimal initialPrice = new BigDecimal(csvRow.get(legacyMapping.getInitialPrice()));
                    Boolean isIncoming = parseBoolean(csvRow.get(legacyMapping.getIsIncoming()));
                    String trunk = csvRow.get(legacyMapping.getTrunk());
                    String initialTrunk = csvRow.get(legacyMapping.getInitialTrunk());
                    Long employeeId = parseLongId(csvRow.get(legacyMapping.getEmployeeId()));
                    String employeeTransfer = csvRow.get(legacyMapping.getEmployeeTransfer());
                    Integer transferCause = Integer.parseInt(csvRow.get(legacyMapping.getTransferCause()));
                    Integer assignmentCause = Integer.parseInt(csvRow.get(legacyMapping.getAssignmentCause()));
                    Long destinationEmployeeId = parseLongId(csvRow.get(legacyMapping.getDestinationEmployeeId()));
                    Long fileInfoId = parseLongId(csvRow.get(legacyMapping.getFileInfoId()));
                    Long centralizedId = parseLongId(csvRow.get(legacyMapping.getCentralizedId()));
                    String originIp = csvRow.get(legacyMapping.getOriginIp());
                    CallRecord callRecord = CallRecord.builder()
                            .id(id)
                            .dial(dial)
                            .commLocationId(commLocationId)
                            .serviceDate(serviceDate)
                            .operatorId(operatorId)
                            .employeeExtension(employeeExtension)
                            .employeeKey(employeeKey)
                            .indicatorId(indicatorId)
                            .destinationPhone(destinationPhone)
                            .duration(duration)
                            .ringCount(ringCount)
                            .telephonyTypeId(telephonyTypeId)
                            .billedAmount(billedAmount)
                            .pricePerMinute(pricePerMinute)
                            .initialPrice(initialPrice)
                            .isIncoming(isIncoming)
                            .trunk(trunk)
                            .initialTrunk(initialTrunk)
                            .employeeId(employeeId)
                            .employeeTransfer(employeeTransfer)
                            .transferCause(transferCause)
                            .assignmentCause(assignmentCause)
                            .destinationEmployeeId(destinationEmployeeId)
                            .fileInfoId(fileInfoId)
                            .centralizedId(centralizedId)
                            .originIp(originIp)
                            .build();
                    try {
                        JpaUtils.saveEntityWithForcedId(callRecord, entityManager);
                    }
                    catch (Exception e){
                        log.error("Failed to save call record data: {}", callRecord);
                    }
                }
            });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void loadEmployeeData(InputStream csvInputStream, EmployeeLegacyMapping legacyMapping){
        try (CsvReader csvReader = new CsvReader(csvInputStream, ",", true)) {
            csvReader.processRowsAsMap(csvRow -> {
                Long id = Long.parseLong(csvRow.get(legacyMapping.getId()));
                if(!JpaUtils.entityExists(Employee.class, id, entityManager)){
                    String name = csvRow.get(legacyMapping.getName());
                    Long subdivisionId = parseLongId(csvRow.get(legacyMapping.getSubdivisionId()));
                    Long costCenterId = parseLongId(csvRow.get(legacyMapping.getCostCenterId()));
                    String extension = csvRow.get(legacyMapping.getExtension());
                    Long communicationLocationId = parseLongId(csvRow.get(legacyMapping.getCommunicationLocationId()));
                    Long jobPositionId = parseLongId(csvRow.get(legacyMapping.getJobPositionId()));
                    String email = csvRow.get(legacyMapping.getEmail());
                    String telephone = csvRow.get(legacyMapping.getPhone());
                    String address = csvRow.get(legacyMapping.getAddress());
                    String idNumber = csvRow.get(legacyMapping.getIdNumber());
                    Employee employee = Employee.builder()
                            .id(id)
                            .name(name)
                            .subdivisionId(subdivisionId)
                            .costCenterId(costCenterId)
                            .extension(extension)
                            .communicationLocationId(communicationLocationId)
                            .jobPositionId(jobPositionId)
                            .email(email)
                            .phone(telephone)
                            .address(address)
                            .idNumber(idNumber)
                            .build();
                    try {
                        JpaUtils.saveEntityWithForcedId(employee, entityManager);
                    }catch (Exception e){
                        log.error("Failed to save employee data: {}", employee);
                    }
                }
            });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void loadCommLocationData(InputStream csvInputStream, CommLocationLegacyMapping legacyMapping){
        try (CsvReader csvReader = new CsvReader(csvInputStream, ",", true)) {
            csvReader.processRowsAsMap(csvRow -> {
                Long id = Long.parseLong(csvRow.get(legacyMapping.getId()));
                if(!JpaUtils.entityExists(CommunicationLocation.class, id, entityManager)){
                    String directory = csvRow.get(legacyMapping.getDirectory());
                    Long plantTypeId = parseLongId(csvRow.get(legacyMapping.getPlantTypeId()));
                    String serial = csvRow.get(legacyMapping.getSerial());
                    Long indicatorId = parseLongId(csvRow.get(legacyMapping.getIndicatorId()));
                    String pbxPrefix = csvRow.get(legacyMapping.getPbxPrefix());
                    String address = csvRow.get(legacyMapping.getAddress());
                    LocalDateTime captureDate = parseDateTime(csvRow.get(legacyMapping.getCaptureDate()));
                    Integer cdrCount = Integer.parseInt(csvRow.get(legacyMapping.getCdrCount()));
                    String fileName = csvRow.get(legacyMapping.getFileName());
                    Long bandGroupId = parseLongId(csvRow.get(legacyMapping.getBandGroupId()));
                    Long headerId = parseLongId(csvRow.get(legacyMapping.getHeaderId()));
                    Integer withoutCaptures = Integer.parseInt(csvRow.get(legacyMapping.getWithoutCaptures()));
                    CommunicationLocation commLocation = CommunicationLocation.builder()
                            .id(id)
                            .directory(directory)
                            .plantTypeId(plantTypeId)
                            .serial(serial)
                            .indicatorId(indicatorId)
                            .pbxPrefix(pbxPrefix)
                            .address(address)
                            .captureDate(captureDate)
                            .cdrCount(cdrCount)
                            .fileName(fileName)
                            .bandGroupId(bandGroupId)
                            .headerId(headerId)
                            .withoutCaptures(withoutCaptures)
                            .build();
                    try {
                        JpaUtils.saveEntityWithForcedId(commLocation, entityManager);
                    }catch (Exception e){
                        log.error("Failed to save comm location data: {}", commLocation);
                    }
                }
            });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void loadPlantTypeData(InputStream csvInputStream, PlantTypeLegacyMapping legacyMapping){
        try (CsvReader csvReader = new CsvReader(csvInputStream, ",", true)) {
            csvReader.processRowsAsMap(csvRow -> {
                Long id = Long.parseLong(csvRow.get(legacyMapping.getId()));
                if(!JpaUtils.entityExists(PlantType.class, id, entityManager)){
                    String name = csvRow.get(legacyMapping.getName());
                    LocalDateTime createdDate = parseDateTime(csvRow.get(legacyMapping.getCreatedDate()));
                    String createdBy = csvRow.get(legacyMapping.getCreatedBy());
                    LocalDateTime lastModifiedDate = parseDateTime(csvRow.get(legacyMapping.getLastModifiedDate()));
                    String lastModifiedBy = csvRow.get(legacyMapping.getLastModifiedBy());
                    PlantType plantType = PlantType.builder()
                            .id(id)
                            .name(name)
                            .createdDate(createdDate)
                            .createdBy(createdBy)
                            .lastModifiedDate(lastModifiedDate)
                            .lastModifiedBy(lastModifiedBy)
                            .build();
                    try {
                        JpaUtils.saveEntityWithForcedId(plantType, entityManager);
                    }catch (Exception e){
                        log.error("Failed to save plant type data: {}", plantType);
                    }
                }
            });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void loadJobPositionData(InputStream csvInputStream, JobPositionLegacyMapping legacyMapping){
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
    
    public boolean parseBoolean(String value) {
        return value != null && (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("1"));
    }
}