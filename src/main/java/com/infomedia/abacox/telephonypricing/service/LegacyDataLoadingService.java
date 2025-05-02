package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.component.csv.CsvReader;
import com.infomedia.abacox.telephonypricing.component.utils.DBUtils;
import com.infomedia.abacox.telephonypricing.dto.callrecord.CallRecordLegacyMapping;
import com.infomedia.abacox.telephonypricing.dto.commlocation.CommLocationLegacyMapping;
import com.infomedia.abacox.telephonypricing.dto.costcenter.CostCenterLegacyMapping;
import com.infomedia.abacox.telephonypricing.dto.employee.EmployeeLegacyMapping;
import com.infomedia.abacox.telephonypricing.dto.extensionrange.ExtensionRangeLegacyMapping;
import com.infomedia.abacox.telephonypricing.dto.indicator.IndicatorLegacyMapping;
import com.infomedia.abacox.telephonypricing.dto.jobposition.JobPositionLegacyMapping;
import com.infomedia.abacox.telephonypricing.dto.operator.OperatorLegacyMapping;
import com.infomedia.abacox.telephonypricing.dto.planttype.PlantTypeLegacyMapping;
import com.infomedia.abacox.telephonypricing.dto.specialservice.SpecialServiceLegacyMapping;
import com.infomedia.abacox.telephonypricing.dto.subdivision.SubdivisionLegacyMapping;
import com.infomedia.abacox.telephonypricing.dto.telephonytype.TelephonyTypeLegacyMapping;
import com.infomedia.abacox.telephonypricing.dto.band.BandLegacyMapping;
import com.infomedia.abacox.telephonypricing.dto.bandindicator.BandIndicatorLegacyMapping;
import com.infomedia.abacox.telephonypricing.dto.callcategory.CallCategoryLegacyMapping;
import com.infomedia.abacox.telephonypricing.dto.city.CityLegacyMapping;
import com.infomedia.abacox.telephonypricing.dto.company.CompanyLegacyMapping;
import com.infomedia.abacox.telephonypricing.dto.contact.ContactLegacyMapping;
import com.infomedia.abacox.telephonypricing.dto.origincountry.OriginCountryLegacyMapping;
import com.infomedia.abacox.telephonypricing.dto.prefix.PrefixLegacyMapping;
import com.infomedia.abacox.telephonypricing.dto.series.SeriesLegacyMapping;
import com.infomedia.abacox.telephonypricing.dto.trunk.TrunkLegacyMapping;
import com.infomedia.abacox.telephonypricing.entity.*;
import com.infomedia.abacox.telephonypricing.entity.superclass.ActivableEntity;
import com.infomedia.abacox.telephonypricing.entity.superclass.AuditedEntity;
import com.infomedia.abacox.telephonypricing.dto.superclass.ActivableLegacyMapping;
import com.infomedia.abacox.telephonypricing.dto.superclass.AuditedLegacyMapping;

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

    // --- Load Methods ---

    public void loadIndicatorData(InputStream csvInputStream, IndicatorLegacyMapping legacyMapping) {
        try (CsvReader csvReader = new CsvReader(csvInputStream, ",", true)) {
            csvReader.processRowsAsMap(csvRow -> {
                Long id = parseLongId(csvRow.get(legacyMapping.getId())); // Parse ID first
                if (id == null) {
                    log.warn("Skipping row due to invalid or missing ID: {}", csvRow);
                    return;
                }
                if(!DBUtils.entityExists(Indicator.class, id, entityManager)){
                    String departmentCountry = csvRow.get(legacyMapping.getDepartmentCountry());
                    String cityName = csvRow.get(legacyMapping.getCityName());
                    Long operatorId = parseLongId(csvRow.get(legacyMapping.getOperatorId()));
                    Long originCountryId = parseLongId(csvRow.get(legacyMapping.getOriginCountryId()));
                    Long telephonyTypeId = parseLongId(csvRow.get(legacyMapping.getTelephonyTypeId()));
                    Indicator indicator = Indicator.builder()
                            .id(id) // Set the parsed ID
                            .telephonyTypeId(telephonyTypeId)
                            .departmentCountry(departmentCountry)
                            .cityName(cityName)
                            .operatorId(operatorId)
                            .originCountryId(originCountryId)
                            .build();
                    // Set activable fields if they exist in the mapping
                    setActivableFields(indicator, csvRow, legacyMapping, id); // Pass ID for logging
                    try {
                        DBUtils.saveEntityWithForcedId(indicator, entityManager);
                    }catch (Exception e){
                        log.error("Failed to save indicator data with ID {}: {}", id, indicator, e);
                    }
                }
            });
        } catch (IOException e) {
            log.error("Error reading Indicator CSV data", e);
            throw new RuntimeException(e);
        }
    }

    public void loadTelephonyTypeData(InputStream csvInputStream, TelephonyTypeLegacyMapping legacyMapping) {
        try (CsvReader csvReader = new CsvReader(csvInputStream, ",", true)) {
            csvReader.processRowsAsMap(csvRow -> {
                Long id = parseLongId(csvRow.get(legacyMapping.getId()));
                if (id == null) {
                    log.warn("Skipping row due to invalid or missing ID: {}", csvRow);
                    return;
                }
                if(!DBUtils.entityExists(TelephonyType.class, id, entityManager)){
                    String name = csvRow.get(legacyMapping.getName());
                    Long callCategoryId = parseLongId(csvRow.get(legacyMapping.getCallCategoryId()));
                    Boolean usesTrunks = parseBoolean(csvRow.get(legacyMapping.getUsesTrunks()));
                    TelephonyType telephonyType = TelephonyType.builder()
                            .id(id)
                            .name(name)
                            .callCategoryId(callCategoryId)
                            .usesTrunks(usesTrunks)
                            .build();
                    setActivableFields(telephonyType, csvRow, legacyMapping, id); // Pass ID
                    try {
                        DBUtils.saveEntityWithForcedId(telephonyType, entityManager);
                    }catch (Exception e){
                        log.error("Failed to save telephony type data with ID {}: {}", id, telephonyType, e);
                    }
                }
            });
        } catch (IOException e) {
            log.error("Error reading TelephonyType CSV data", e);
            throw new RuntimeException(e);
        }
    }

    public void loadOperatorData(InputStream csvInputStream, OperatorLegacyMapping legacyMapping) {
        try (CsvReader csvReader = new CsvReader(csvInputStream, ",", true)) {
            csvReader.processRowsAsMap(csvRow -> {
                Long id = parseLongId(csvRow.get(legacyMapping.getId()));
                if (id == null) {
                    log.warn("Skipping row due to invalid or missing ID: {}", csvRow);
                    return;
                }
                if(!DBUtils.entityExists(Operator.class, id, entityManager)){
                    String name = csvRow.get(legacyMapping.getName());
                    Long originCountryId = parseLongId(csvRow.get(legacyMapping.getOriginCountryId()));
                    Operator operator = Operator.builder()
                            .id(id)
                            .name(name)
                            .originCountryId(originCountryId)
                            .build();
                    setActivableFields(operator, csvRow, legacyMapping, id); // Pass ID
                    try {
                        DBUtils.saveEntityWithForcedId(operator, entityManager);
                    }catch (Exception e){
                        log.error("Failed to save operator data with ID {}: {}", id, operator, e);
                    }
                }
            });
        } catch (IOException e) {
            log.error("Error reading Operator CSV data", e);
            throw new RuntimeException(e);
        }
    }

    public void loadCallRecordData(InputStream csvInputStream, CallRecordLegacyMapping legacyMapping){
        try (CsvReader csvReader = new CsvReader(csvInputStream, ",", true)) {
            csvReader.processRowsAsMap(csvRow -> {
                Long id = parseLongId(csvRow.get(legacyMapping.getId()));
                if (id == null) {
                    log.warn("Skipping row due to invalid or missing ID: {}", csvRow);
                    return;
                }
                if(!DBUtils.entityExists(CallRecord.class, id, entityManager)){
                    String dial = csvRow.get(legacyMapping.getDial());
                    Long commLocationId = parseLongId(csvRow.get(legacyMapping.getCommLocationId()));
                    LocalDateTime serviceDate = parseDateTime(csvRow.get(legacyMapping.getServiceDate()));
                    Long operatorId = parseLongId(csvRow.get(legacyMapping.getOperatorId()));
                    String employeeExtension = csvRow.get(legacyMapping.getEmployeeExtension());
                    String employeeKey = csvRow.get(legacyMapping.getEmployeeAuthCode());
                    Long indicatorId = parseLongId(csvRow.get(legacyMapping.getIndicatorId()));
                    String destinationPhone = csvRow.get(legacyMapping.getDestinationPhone());
                    Integer duration = parseInteger(csvRow.get(legacyMapping.getDuration()));
                    Integer ringCount = parseInteger(csvRow.get(legacyMapping.getRingCount()));
                    Long telephonyTypeId = parseLongId(csvRow.get(legacyMapping.getTelephonyTypeId()));
                    BigDecimal billedAmount = parseBigDecimal(csvRow.get(legacyMapping.getBilledAmount()));
                    BigDecimal pricePerMinute = parseBigDecimal(csvRow.get(legacyMapping.getPricePerMinute()));
                    BigDecimal initialPrice = parseBigDecimal(csvRow.get(legacyMapping.getInitialPrice()));
                    Boolean isIncoming = parseBoolean(csvRow.get(legacyMapping.getIsIncoming()));
                    String trunk = csvRow.get(legacyMapping.getTrunk());
                    String initialTrunk = csvRow.get(legacyMapping.getInitialTrunk());
                    Long employeeId = parseLongId(csvRow.get(legacyMapping.getEmployeeId()));
                    String employeeTransfer = csvRow.get(legacyMapping.getEmployeeTransfer());
                    Integer transferCause = parseInteger(csvRow.get(legacyMapping.getTransferCause()));
                    Integer assignmentCause = parseInteger(csvRow.get(legacyMapping.getAssignmentCause()));
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
                            .employeeAuthCode(employeeKey)
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
                            .build();
                    setAuditedFields(callRecord, csvRow, legacyMapping, id); // Pass ID
                    try {
                        DBUtils.saveEntityWithForcedId(callRecord, entityManager);
                    }
                    catch (Exception e){
                        log.error("Failed to save call record data with ID {}: {}", id, callRecord, e);
                    }
                }
            });
        } catch (IOException e) {
            log.error("Error reading CallRecord CSV data", e);
            throw new RuntimeException(e);
        }
    }

    public void loadEmployeeData(InputStream csvInputStream, EmployeeLegacyMapping legacyMapping){
        try (CsvReader csvReader = new CsvReader(csvInputStream, ",", true)) {
            csvReader.processRowsAsMap(csvRow -> {
                Long id = parseLongId(csvRow.get(legacyMapping.getId()));
                if (id == null) {
                    log.warn("Skipping row due to invalid or missing ID: {}", csvRow);
                    return;
                }
                if(!DBUtils.entityExists(Employee.class, id, entityManager)){
                    String name = csvRow.get(legacyMapping.getName());
                    Long subdivisionId = parseLongId(csvRow.get(legacyMapping.getSubdivisionId()));
                    Long costCenterId = parseLongId(csvRow.get(legacyMapping.getCostCenterId()));
                    String authCode = csvRow.get(legacyMapping.getAuthCode());
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
                            .authCode(authCode)
                            .extension(extension)
                            .communicationLocationId(communicationLocationId)
                            .jobPositionId(jobPositionId)
                            .email(email)
                            .phone(telephone)
                            .address(address)
                            .idNumber(idNumber)
                            .build();
                    setActivableFields(employee, csvRow, legacyMapping, id); // Pass ID
                    try {
                        DBUtils.saveEntityWithForcedId(employee, entityManager);
                    }catch (Exception e){
                        log.error("Failed to save employee data with ID {}: {}", id, employee, e);
                    }
                }
            });
        } catch (IOException e) {
            log.error("Error reading Employee CSV data", e);
            throw new RuntimeException(e);
        }
    }

    public void loadCommLocationData(InputStream csvInputStream, CommLocationLegacyMapping legacyMapping){
        try (CsvReader csvReader = new CsvReader(csvInputStream, ",", true)) {
            csvReader.processRowsAsMap(csvRow -> {
                Long id = parseLongId(csvRow.get(legacyMapping.getId()));
                if (id == null) {
                    log.warn("Skipping row due to invalid or missing ID: {}", csvRow);
                    return;
                }
                if(!DBUtils.entityExists(CommunicationLocation.class, id, entityManager)){
                    String directory = csvRow.get(legacyMapping.getDirectory());
                    Long plantTypeId = parseLongId(csvRow.get(legacyMapping.getPlantTypeId()));
                    String serial = csvRow.get(legacyMapping.getSerial());
                    Long indicatorId = parseLongId(csvRow.get(legacyMapping.getIndicatorId()));
                    String pbxPrefix = csvRow.get(legacyMapping.getPbxPrefix());
                    LocalDateTime captureDate = parseDateTime(csvRow.get(legacyMapping.getCaptureDate()));
                    Integer cdrCount = parseInteger(csvRow.get(legacyMapping.getCdrCount()));
                    String fileName = csvRow.get(legacyMapping.getFileName());
                    Long headerId = parseLongId(csvRow.get(legacyMapping.getHeaderId()));
                    CommunicationLocation commLocation = CommunicationLocation.builder()
                            .id(id)
                            .directory(directory)
                            .plantTypeId(plantTypeId)
                            .serial(serial)
                            .indicatorId(indicatorId)
                            .pbxPrefix(pbxPrefix)
                            .captureDate(captureDate)
                            .cdrCount(cdrCount)
                            .fileName(fileName)
                            .headerId(headerId)
                            .build();
                    setActivableFields(commLocation, csvRow, legacyMapping, id); // Pass ID
                    try {
                        DBUtils.saveEntityWithForcedId(commLocation, entityManager);
                    }catch (Exception e){
                        log.error("Failed to save comm location data with ID {}: {}", id, commLocation, e);
                    }
                }
            });
        } catch (IOException e) {
            log.error("Error reading CommunicationLocation CSV data", e);
            throw new RuntimeException(e);
        }
    }

    public void loadPlantTypeData(InputStream csvInputStream, PlantTypeLegacyMapping legacyMapping){
        try (CsvReader csvReader = new CsvReader(csvInputStream, ",", true)) {
            csvReader.processRowsAsMap(csvRow -> {
                Long id = parseLongId(csvRow.get(legacyMapping.getId()));
                if (id == null) {
                    log.warn("Skipping row due to invalid or missing ID: {}", csvRow);
                    return;
                }
                if(!DBUtils.entityExists(PlantType.class, id, entityManager)){
                    String name = csvRow.get(legacyMapping.getName());
                    PlantType plantType = PlantType.builder()
                            .id(id)
                            .name(name)
                            .build();
                    setActivableFields(plantType, csvRow, legacyMapping, id); // Pass ID
                    try {
                        DBUtils.saveEntityWithForcedId(plantType, entityManager);
                    }catch (Exception e){
                        log.error("Failed to save plant type data with ID {}: {}", id, plantType, e);
                    }
                }
            });
        } catch (IOException e) {
            log.error("Error reading PlantType CSV data", e);
            throw new RuntimeException(e);
        }
    }

    public void loadJobPositionData(InputStream csvInputStream, JobPositionLegacyMapping legacyMapping){
        try (CsvReader csvReader = new CsvReader(csvInputStream, ",", true)) {
            csvReader.processRowsAsMap(csvRow -> {
                Long id = parseLongId(csvRow.get(legacyMapping.getId()));
                if (id == null) {
                    log.warn("Skipping row due to invalid or missing ID: {}", csvRow);
                    return;
                }
                if(!DBUtils.entityExists(JobPosition.class, id, entityManager)){
                    String name = csvRow.get(legacyMapping.getName());
                    JobPosition jobPosition = JobPosition.builder()
                            .id(id)
                            .name(name)
                            .build();
                    setActivableFields(jobPosition, csvRow, legacyMapping, id); // Pass ID
                    try {
                        DBUtils.saveEntityWithForcedId(jobPosition, entityManager);
                    }catch (Exception e){
                        log.error("Failed to save job position data with ID {}: {}", id, jobPosition, e);
                    }
                }
            });
        } catch (IOException e) {
            log.error("Error reading JobPosition CSV data", e);
            throw new RuntimeException(e);
        }
    }

    public void loadCostCenterData(InputStream csvInputStream, CostCenterLegacyMapping legacyMapping) {
        try (CsvReader csvReader = new CsvReader(csvInputStream, ",", true)) {
            Map<Long, CsvReader.CsvRow> costCenterMap = csvReader.readAllRows()
                    .stream().filter(csvRow -> {
                        try {
                            Long id = parseLongId(csvRow.get(legacyMapping.getId()));
                            if (id == null) {
                                log.warn("Skipping CostCenter row due to invalid or missing ID: {}", csvRow);
                                return false;
                            }
                            return !DBUtils.entityExists(CostCenter.class, id, entityManager);
                        } catch (Exception e) { // Catch broader exceptions during filtering
                            log.warn("Skipping CostCenter row due to error during ID check: {}", csvRow, e);
                            return false;
                        }
                    })
                    .collect(Collectors.toMap(csvRow -> parseLongId(csvRow.get(legacyMapping.getId())), Function.identity(), (existing, replacement) -> existing)); // Handle potential duplicate IDs in CSV

            Set<Long> processedIds = new HashSet<>();
            costCenterMap.forEach((id, csvRow) -> {
                // Skip if already processed (redundant check due to filter, but safe)
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
                setActivableFields(costCenter, csvRow, legacyMapping, id); // Pass ID

                insertWithParentRecursively(
                        costCenter,
                        CostCenter::getId,
                        CostCenter::getParentCostCenterId,
                        parentId -> {
                            CsvReader.CsvRow parentRow = costCenterMap.get(parentId);
                            if (parentRow != null) {
                                CostCenter parent = CostCenter.builder()
                                        .id(parentId)
                                        .name(parentRow.get(legacyMapping.getName()))
                                        .originCountryId(parseLongId(parentRow.get(legacyMapping.getOriginCountryId())))
                                        .workOrder(parentRow.get(legacyMapping.getWorkOrder()))
                                        .parentCostCenterId(parseLongId(parentRow.get(legacyMapping.getParentCostCenterId())))
                                        .build();
                                setActivableFields(parent, parentRow, legacyMapping, parentId); // Pass parentId
                                return parent;
                            }
                            return null;
                        },
                        CostCenter.class,
                        processedIds
                );
            });
        } catch (IOException e) {
            log.error("Error reading CostCenter CSV data", e);
            throw new RuntimeException(e);
        }
    }

    public void loadSubdivisionData(InputStream csvInputStream, SubdivisionLegacyMapping legacyMapping) {
        try (CsvReader csvReader = new CsvReader(csvInputStream, ",", true)) {
            Map<Long, CsvReader.CsvRow> subdivisionMap = csvReader.readAllRows()
                    .stream().filter(csvRow -> {
                        try {
                            Long id = parseLongId(csvRow.get(legacyMapping.getId()));
                            if (id == null) {
                                log.warn("Skipping Subdivision row due to invalid or missing ID: {}", csvRow);
                                return false;
                            }
                            return !DBUtils.entityExists(Subdivision.class, id, entityManager);
                        } catch (Exception e) {
                            log.warn("Skipping Subdivision row due to error during ID check: {}", csvRow, e);
                            return false;
                        }
                    })
                    .collect(Collectors.toMap(csvRow -> parseLongId(csvRow.get(legacyMapping.getId())), Function.identity(), (existing, replacement) -> existing)); // Handle potential duplicate IDs in CSV

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
                setActivableFields(subdivision, csvRow, legacyMapping, id); // Pass ID

                insertWithParentRecursively(
                        subdivision,
                        Subdivision::getId,
                        Subdivision::getParentSubdivisionId,
                        parentId -> {
                            CsvReader.CsvRow parentRow = subdivisionMap.get(parentId);
                            if (parentRow != null) {
                                Subdivision parent = Subdivision.builder()
                                        .id(parentId)
                                        .name(parentRow.get(legacyMapping.getName()))
                                        .parentSubdivisionId(parseLongId(parentRow.get(legacyMapping.getParentSubdivisionId())))
                                        .build();
                                setActivableFields(parent, parentRow, legacyMapping, parentId); // Pass parentId
                                return parent;
                            }
                            return null;
                        },
                        Subdivision.class,
                        processedIds
                );
            });
        } catch (IOException e) {
            log.error("Error reading Subdivision CSV data", e);
            throw new RuntimeException(e);
        }
    }

    // --- New Methods for Missing Entities ---

    public void loadBandData(InputStream csvInputStream, BandLegacyMapping legacyMapping) {
        try (CsvReader csvReader = new CsvReader(csvInputStream, ",", true)) {
            csvReader.processRowsAsMap(csvRow -> {
                Long id = parseLongId(csvRow.get(legacyMapping.getId()));
                if (id == null) {
                    log.warn("Skipping row due to invalid or missing ID: {}", csvRow);
                    return;
                }
                if (!DBUtils.entityExists(Band.class, id, entityManager)) {
                    Long prefixId = parseLongId(csvRow.get(legacyMapping.getPrefixId()));
                    String name = csvRow.get(legacyMapping.getName());
                    BigDecimal value = parseBigDecimal(csvRow.get(legacyMapping.getValue()));
                    Boolean vatIncluded = parseBoolean(csvRow.get(legacyMapping.getVatIncluded()));
                    Long originIndicatorId = parseLongId(csvRow.get(legacyMapping.getOriginIndicatorId()));
                    Long reference = parseLongId(csvRow.get(legacyMapping.getReference()));
                    Band band = Band.builder()
                            .id(id)
                            .prefixId(prefixId)
                            .name(name)
                            .value(value)
                            .originIndicatorId(originIndicatorId)
                            .reference(reference)
                            .vatIncluded(vatIncluded)
                            .build();
                    setActivableFields(band, csvRow, legacyMapping, id); // Pass ID
                    try {
                        DBUtils.saveEntityWithForcedId(band, entityManager);
                    } catch (Exception e) {
                        log.error("Failed to save band data with ID {}: {}", id, band, e);
                    }
                }
            });
        } catch (IOException e) {
            log.error("Error reading Band CSV data", e);
            throw new RuntimeException(e);
        }
    }

    public void loadBandIndicatorData(InputStream csvInputStream, BandIndicatorLegacyMapping legacyMapping) {
        try (CsvReader csvReader = new CsvReader(csvInputStream, ",", true)) {
            csvReader.processRowsAsMap(csvRow -> {
                Long id = parseLongId(csvRow.get(legacyMapping.getId()));
                if (id == null) {
                    log.warn("Skipping row due to invalid or missing ID: {}", csvRow);
                    return;
                }
                if (!DBUtils.entityExists(BandIndicator.class, id, entityManager)) {
                    Long bandId = parseLongId(csvRow.get(legacyMapping.getBandId()));
                    Long indicatorId = parseLongId(csvRow.get(legacyMapping.getIndicatorId()));

                    BandIndicator bandIndicator = BandIndicator.builder()
                            .id(id)
                            .bandId(bandId)
                            .indicatorId(indicatorId)
                            .build();
                    setAuditedFields(bandIndicator, csvRow, legacyMapping, id); // Pass ID
                    try {
                        DBUtils.saveEntityWithForcedId(bandIndicator, entityManager);
                    } catch (Exception e) {
                        log.error("Failed to save band indicator data with ID {}: {}", id, bandIndicator, e);
                    }
                }
            });
        } catch (IOException e) {
            log.error("Error reading BandIndicator CSV data", e);
            throw new RuntimeException(e);
        }
    }

    public void loadCallCategoryData(InputStream csvInputStream, CallCategoryLegacyMapping legacyMapping) {
        try (CsvReader csvReader = new CsvReader(csvInputStream, ",", true)) {
            csvReader.processRowsAsMap(csvRow -> {
                Long id = parseLongId(csvRow.get(legacyMapping.getId()));
                if (id == null) {
                    log.warn("Skipping row due to invalid or missing ID: {}", csvRow);
                    return;
                }
                if (!DBUtils.entityExists(CallCategory.class, id, entityManager)) {
                    String name = csvRow.get(legacyMapping.getName());

                    CallCategory callCategory = CallCategory.builder()
                            .id(id)
                            .name(name)
                            .build();
                    setActivableFields(callCategory, csvRow, legacyMapping, id); // Pass ID
                    try {
                        DBUtils.saveEntityWithForcedId(callCategory, entityManager);
                    } catch (Exception e) {
                        log.error("Failed to save call category data with ID {}: {}", id, callCategory, e);
                    }
                }
            });
        } catch (IOException e) {
            log.error("Error reading CallCategory CSV data", e);
            throw new RuntimeException(e);
        }
    }

    public void loadCityData(InputStream csvInputStream, CityLegacyMapping legacyMapping) {
        try (CsvReader csvReader = new CsvReader(csvInputStream, ",", true)) {
            csvReader.processRowsAsMap(csvRow -> {
                Long id = parseLongId(csvRow.get(legacyMapping.getId()));
                if (id == null) {
                    log.warn("Skipping row due to invalid or missing ID: {}", csvRow);
                    return;
                }
                if (!DBUtils.entityExists(City.class, id, entityManager)) {
                    String department = csvRow.get(legacyMapping.getDepartment());
                    String classification = csvRow.get(legacyMapping.getClassification());
                    String municipality = csvRow.get(legacyMapping.getMunicipality());
                    String municipalCapital = csvRow.get(legacyMapping.getMunicipalCapital());
                    String latitude = csvRow.get(legacyMapping.getLatitude());
                    String longitude = csvRow.get(legacyMapping.getLongitude());
                    Integer altitude = parseInteger(csvRow.get(legacyMapping.getAltitude()));
                    Integer northCoordinate = parseInteger(csvRow.get(legacyMapping.getNorthCoordinate()));
                    Integer eastCoordinate = parseInteger(csvRow.get(legacyMapping.getEastCoordinate()));
                    String origin = csvRow.get(legacyMapping.getOrigin());

                    City city = City.builder()
                            .id(id)
                            .department(department)
                            .classification(classification)
                            .municipality(municipality)
                            .municipalCapital(municipalCapital)
                            .latitude(latitude)
                            .longitude(longitude)
                            .altitude(altitude)
                            .northCoordinate(northCoordinate)
                            .eastCoordinate(eastCoordinate)
                            .origin(origin)
                            .build();
                    setActivableFields(city, csvRow, legacyMapping, id); // Pass ID
                    try {
                        DBUtils.saveEntityWithForcedId(city, entityManager);
                    } catch (Exception e) {
                        log.error("Failed to save city data with ID {}: {}", id, city, e);
                    }
                }
            });
        } catch (IOException e) {
            log.error("Error reading City CSV data", e);
            throw new RuntimeException(e);
        }
    }

    public void loadCompanyData(InputStream csvInputStream, CompanyLegacyMapping legacyMapping) {
        try (CsvReader csvReader = new CsvReader(csvInputStream, ",", true)) {
            csvReader.processRowsAsMap(csvRow -> {
                Long id = parseLongId(csvRow.get(legacyMapping.getId()));
                if (id == null) {
                    log.warn("Skipping row due to invalid or missing ID: {}", csvRow);
                    return;
                }
                if (!DBUtils.entityExists(Company.class, id, entityManager)) {
                    String additionalInfo = csvRow.get(legacyMapping.getAdditionalInfo());
                    String address = csvRow.get(legacyMapping.getAddress());
                    String name = csvRow.get(legacyMapping.getName());
                    String taxId = csvRow.get(legacyMapping.getTaxId());
                    String legalName = csvRow.get(legacyMapping.getLegalName());
                    String website = csvRow.get(legacyMapping.getWebsite());
                    Integer indicatorId = parseIntegerId(csvRow.get(legacyMapping.getIndicatorId())); // Assuming indicatorId is Integer in Company

                    Company company = Company.builder()
                            .id(id)
                            .additionalInfo(additionalInfo)
                            .address(address)
                            .name(name)
                            .taxId(taxId)
                            .legalName(legalName)
                            .website(website)
                            .indicatorId(indicatorId)
                            .build();
                    setActivableFields(company, csvRow, legacyMapping, id); // Pass ID
                    try {
                        DBUtils.saveEntityWithForcedId(company, entityManager);
                    } catch (Exception e) {
                        log.error("Failed to save company data with ID {}: {}", id, company, e);
                    }
                }
            });
        } catch (IOException e) {
            log.error("Error reading Company CSV data", e);
            throw new RuntimeException(e);
        }
    }

    public void loadContactData(InputStream csvInputStream, ContactLegacyMapping legacyMapping) {
        try (CsvReader csvReader = new CsvReader(csvInputStream, ",", true)) {
            csvReader.processRowsAsMap(csvRow -> {
                Long id = parseLongId(csvRow.get(legacyMapping.getId()));
                if (id == null) {
                    log.warn("Skipping row due to invalid or missing ID: {}", csvRow);
                    return;
                }
                if (!DBUtils.entityExists(Contact.class, id, entityManager)) {
                    Boolean contactType = parseBoolean(csvRow.get(legacyMapping.getContactType()));
                    Long employeeId = parseLongId(csvRow.get(legacyMapping.getEmployeeId()));
                    Long companyId = parseLongId(csvRow.get(legacyMapping.getCompanyId()));
                    String phoneNumber = csvRow.get(legacyMapping.getPhoneNumber());
                    String name = csvRow.get(legacyMapping.getName());
                    String description = csvRow.get(legacyMapping.getDescription());
                    Long indicatorId = parseLongId(csvRow.get(legacyMapping.getIndicatorId()));

                    Contact contact = Contact.builder()
                            .id(id)
                            .contactType(contactType)
                            .employeeId(employeeId)
                            .companyId(companyId)
                            .phoneNumber(phoneNumber)
                            .name(name)
                            .description(description)
                            .indicatorId(indicatorId)
                            .build();
                    setActivableFields(contact, csvRow, legacyMapping, id); // Pass ID
                    try {
                        DBUtils.saveEntityWithForcedId(contact, entityManager);
                    } catch (Exception e) {
                        log.error("Failed to save contact data with ID {}: {}", id, contact, e);
                    }
                }
            });
        } catch (IOException e) {
            log.error("Error reading Contact CSV data", e);
            throw new RuntimeException(e);
        }
    }

    public void loadOriginCountryData(InputStream csvInputStream, OriginCountryLegacyMapping legacyMapping) {
        try (CsvReader csvReader = new CsvReader(csvInputStream, ",", true)) {
            csvReader.processRowsAsMap(csvRow -> {
                Long id = parseLongId(csvRow.get(legacyMapping.getId()));
                if (id == null) {
                    log.warn("Skipping row due to invalid or missing ID: {}", csvRow);
                    return;
                }
                if (!DBUtils.entityExists(OriginCountry.class, id, entityManager)) {
                    String currencySymbol = csvRow.get(legacyMapping.getCurrencySymbol());
                    String name = csvRow.get(legacyMapping.getName());
                    String code = csvRow.get(legacyMapping.getCode());

                    OriginCountry originCountry = OriginCountry.builder()
                            .id(id)
                            .currencySymbol(currencySymbol)
                            .name(name)
                            .code(code)
                            .build();
                    setActivableFields(originCountry, csvRow, legacyMapping, id); // Pass ID
                    try {
                        DBUtils.saveEntityWithForcedId(originCountry, entityManager);
                    } catch (Exception e) {
                        log.error("Failed to save origin country data with ID {}: {}", id, originCountry, e);
                    }
                }
            });
        } catch (IOException e) {
            log.error("Error reading OriginCountry CSV data", e);
            throw new RuntimeException(e);
        }
    }

    public void loadPrefixData(InputStream csvInputStream, PrefixLegacyMapping legacyMapping) {
        try (CsvReader csvReader = new CsvReader(csvInputStream, ",", true)) {
            csvReader.processRowsAsMap(csvRow -> {
                Long id = parseLongId(csvRow.get(legacyMapping.getId()));
                if (id == null) {
                    log.warn("Skipping row due to invalid or missing ID: {}", csvRow);
                    return;
                }
                if (!DBUtils.entityExists(Prefix.class, id, entityManager)) {
                    Long operatorId = parseLongId(csvRow.get(legacyMapping.getOperatorId()));
                    Long telephoneTypeId = parseLongId(csvRow.get(legacyMapping.getTelephoneTypeId()));
                    String code = csvRow.get(legacyMapping.getCode());
                    BigDecimal baseValue = parseBigDecimal(csvRow.get(legacyMapping.getBaseValue()));
                    Boolean bandOk = parseBoolean(csvRow.get(legacyMapping.getBandOk()));
                    Boolean vatIncluded = parseBoolean(csvRow.get(legacyMapping.getVatIncluded()));
                    BigDecimal vatValue = parseBigDecimal(csvRow.get(legacyMapping.getVatValue()));

                    Prefix prefix = Prefix.builder()
                            .id(id)
                            .operatorId(operatorId)
                            .telephoneTypeId(telephoneTypeId)
                            .code(code)
                            .baseValue(baseValue)
                            .bandOk(bandOk)
                            .vatIncluded(vatIncluded)
                            .vatValue(vatValue)
                            .build();
                    setActivableFields(prefix, csvRow, legacyMapping, id); // Pass ID
                    try {
                        DBUtils.saveEntityWithForcedId(prefix, entityManager);
                    } catch (Exception e) {
                        log.error("Failed to save prefix data with ID {}: {}", id, prefix, e);
                    }
                }
            });
        } catch (IOException e) {
            log.error("Error reading Prefix CSV data", e);
            throw new RuntimeException(e);
        }
    }

    public void loadSeriesData(InputStream csvInputStream, SeriesLegacyMapping legacyMapping) {
        try (CsvReader csvReader = new CsvReader(csvInputStream, ",", true)) {
            csvReader.processRowsAsMap(csvRow -> {
                Long id = parseLongId(csvRow.get(legacyMapping.getId()));
                if (id == null) {
                    log.warn("Skipping row due to invalid or missing ID: {}", csvRow);
                    return;
                }
                if (!DBUtils.entityExists(Series.class, id, entityManager)) {
                    Long indicatorId = parseLongId(csvRow.get(legacyMapping.getIndicatorId()));
                    Integer ndc = parseInteger(csvRow.get(legacyMapping.getNdc()));
                    Integer initialNumber = parseInteger(csvRow.get(legacyMapping.getInitialNumber()));
                    Integer finalNumber = parseInteger(csvRow.get(legacyMapping.getFinalNumber()));
                    String company = csvRow.get(legacyMapping.getCompany());

                    Series series = Series.builder()
                            .id(id)
                            .indicatorId(indicatorId)
                            .ndc(ndc)
                            .initialNumber(initialNumber)
                            .finalNumber(finalNumber)
                            .company(company)
                            .build();
                    setActivableFields(series, csvRow, legacyMapping, id); // Pass ID
                    try {
                        DBUtils.saveEntityWithForcedId(series, entityManager);
                    } catch (Exception e) {
                        log.error("Failed to save series data with ID {}: {}", id, series, e);
                    }
                }
            });
        } catch (IOException e) {
            log.error("Error reading Series CSV data", e);
            throw new RuntimeException(e);
        }
    }

    public void loadExtensionRangeData(InputStream csvInputStream, ExtensionRangeLegacyMapping legacyMapping) {
        try (CsvReader csvReader = new CsvReader(csvInputStream, ",", true)) {
            csvReader.processRowsAsMap(csvRow -> {
                Long id = parseLongId(csvRow.get(legacyMapping.getId()));
                if (id == null) {
                    log.warn("Skipping row due to invalid or missing ID: {}", csvRow);
                    return;
                }
                if (!DBUtils.entityExists(ExtensionRange.class, id, entityManager)) {
                    Long commLocationId = parseLongId(csvRow.get(legacyMapping.getCommLocationId()));
                    Long subdivisionId = parseLongId(csvRow.get(legacyMapping.getSubdivisionId()));
                    String prefix = csvRow.get(legacyMapping.getPrefix());
                    Long rangeStart = parseLong(csvRow.get(legacyMapping.getRangeStart())); // Use parseLong for non-ID Longs
                    Long rangeEnd = parseLong(csvRow.get(legacyMapping.getRangeEnd()));     // Use parseLong for non-ID Longs
                    Long costCenterId = parseLongId(csvRow.get(legacyMapping.getCostCenterId()));

                    ExtensionRange extensionRange = ExtensionRange.builder()
                            .id(id)
                            .commLocationId(commLocationId)
                            .subdivisionId(subdivisionId)
                            .prefix(prefix)
                            .rangeStart(rangeStart)
                            .rangeEnd(rangeEnd)
                            .costCenterId(costCenterId)
                            .build();
                    setActivableFields(extensionRange, csvRow, legacyMapping, id); // Pass ID
                    try {
                        DBUtils.saveEntityWithForcedId(extensionRange, entityManager);
                    } catch (Exception e) {
                        log.error("Failed to save extension range data with ID {}: {}", id, extensionRange, e);
                    }
                }
            });
        } catch (IOException e) {
            log.error("Error reading ExtensionRange CSV data", e);
            throw new RuntimeException(e);
        }
    }

    public void loadSpecialServiceData(InputStream csvInputStream, SpecialServiceLegacyMapping legacyMapping) {
        try (CsvReader csvReader = new CsvReader(csvInputStream, ",", true)) {
            csvReader.processRowsAsMap(csvRow -> {
                Long id = parseLongId(csvRow.get(legacyMapping.getId()));
                if (id == null) {
                    log.warn("Skipping row due to invalid or missing ID: {}", csvRow);
                    return;
                }
                if (!DBUtils.entityExists(SpecialService.class, id, entityManager)) {
                    Long indicatorId = parseLongId(csvRow.get(legacyMapping.getIndicatorId()));
                    String phoneNumber = csvRow.get(legacyMapping.getPhoneNumber());
                    BigDecimal value = parseBigDecimal(csvRow.get(legacyMapping.getValue()));
                    BigDecimal vatAmount = parseBigDecimal(csvRow.get(legacyMapping.getVatAmount()));
                    Boolean vatIncluded = parseBoolean(csvRow.get(legacyMapping.getVatIncluded()));
                    String description = csvRow.get(legacyMapping.getDescription());
                    Long originCountryId = parseLongId(csvRow.get(legacyMapping.getOriginCountryId()));

                    SpecialService specialService = SpecialService.builder()
                            .id(id)
                            .indicatorId(indicatorId)
                            .phoneNumber(phoneNumber)
                            .value(value)
                            .vatAmount(vatAmount)
                            .vatIncluded(vatIncluded)
                            .description(description)
                            .originCountryId(originCountryId)
                            .build();
                    setActivableFields(specialService, csvRow, legacyMapping, id);
                    try {
                        DBUtils.saveEntityWithForcedId(specialService, entityManager);
                    } catch (Exception e) {
                        log.error("Failed to save special service data with ID {}: {}", id, specialService, e);
                    }
                }
            });
        } catch (IOException e) {
            log.error("Error reading SpecialService CSV data", e);
            throw new RuntimeException(e);
        }
    }

    public void loadTrunkData(InputStream csvInputStream, TrunkLegacyMapping legacyMapping) {
        try (CsvReader csvReader = new CsvReader(csvInputStream, ",", true)) {
            csvReader.processRowsAsMap(csvRow -> {
                Long id = parseLongId(csvRow.get(legacyMapping.getId()));
                if (id == null) {
                    log.warn("Skipping row due to invalid or missing ID: {}", csvRow);
                    return;
                }
                if (!DBUtils.entityExists(Trunk.class, id, entityManager)) {
                    Long commLocationId = parseLongId(csvRow.get(legacyMapping.getCommLocationId()));
                    String description = csvRow.get(legacyMapping.getDescription());
                    String name = csvRow.get(legacyMapping.getName());
                    Long operatorId = parseLongId(csvRow.get(legacyMapping.getOperatorId()));
                    Boolean noPbxPrefix = parseBoolean(csvRow.get(legacyMapping.getNoPbxPrefix()));
                    Integer channels = parseInteger(csvRow.get(legacyMapping.getChannels()));

                    Trunk trunk = Trunk.builder()
                            .id(id)
                            .commLocationId(commLocationId)
                            .description(description)
                            .name(name)
                            .operatorId(operatorId)
                            .noPbxPrefix(noPbxPrefix)
                            .channels(channels)
                            .build();
                    setActivableFields(trunk, csvRow, legacyMapping, id); // Pass ID
                    try {
                        DBUtils.saveEntityWithForcedId(trunk, entityManager);
                    } catch (Exception e) {
                        log.error("Failed to save trunk data with ID {}: {}", id, trunk, e);
                    }
                }
            });
        } catch (IOException e) {
            log.error("Error reading Trunk CSV data", e);
            throw new RuntimeException(e);
        }
    }


    // --- Helper Methods ---

    private Long parseLongId(String idString) {
        if (idString == null || idString.isEmpty() || idString.equalsIgnoreCase("null")) {
            return null;
        }
        try {
            // Handle potential floating point numbers in ID columns if source data is messy
            if (idString.contains(".")) {
                idString = idString.substring(0, idString.indexOf('.'));
            }
            long id = Long.parseLong(idString);
            return id > 0 ? id : null; // Assuming IDs <= 0 are invalid or represent null
        } catch (NumberFormatException e) {
            log.warn("Could not parse Long ID: '{}', returning null.", idString);
            return null;
        }
    }

    private Long parseLong(String longString){
        if (longString == null || longString.isEmpty() || longString.equalsIgnoreCase("null")) {
            return null;
        }
        try {
            if (longString.contains(".")) {
                longString = longString.substring(0, longString.indexOf('.'));
            }
            return Long.parseLong(longString);
        } catch (NumberFormatException e) {
            log.warn("Could not parse Long: '{}', returning null.", longString);
            return null; // Or return 0
        }
    }

    private Integer parseIntegerId(String idString) {
        if (idString == null || idString.isEmpty() || idString.equalsIgnoreCase("null")) {
            return null;
        }
        try {
            if (idString.contains(".")) {
                idString = idString.substring(0, idString.indexOf('.'));
            }
            int id = Integer.parseInt(idString);
            return id > 0 ? id : null; // Assuming IDs <= 0 are invalid or represent null
        } catch (NumberFormatException e) {
            log.warn("Could not parse Integer ID: '{}', returning null.", idString);
            return null;
        }
    }

    private Integer parseInteger(String intString) {
        if (intString == null || intString.isEmpty() || intString.equalsIgnoreCase("null")) {
            return null; // Or return 0 based on requirements, check @ColumnDefault
        }
        try {
            if (intString.contains(".")) {
                intString = intString.substring(0, intString.indexOf('.'));
            }
            return Integer.parseInt(intString);
        } catch (NumberFormatException e) {
            log.warn("Could not parse Integer: '{}', returning null.", intString);
            return null; // Or return 0
        }
    }

    private BigDecimal parseBigDecimal(String decimalString) {
        if (decimalString == null || decimalString.isEmpty() || decimalString.equalsIgnoreCase("null")) {
            return null; // Or return BigDecimal.ZERO based on requirements, check @ColumnDefault
        }
        try {
            // Handle potential commas as decimal separators if needed
            // decimalString = decimalString.replace(',', '.');
            return new BigDecimal(decimalString);
        } catch (NumberFormatException e) {
            log.warn("Could not parse BigDecimal: '{}', returning null.", decimalString);
            return null; // Or return BigDecimal.ZERO
        }
    }

    private LocalDateTime parseDateTime(String dateString) {
        if (dateString == null || dateString.isEmpty() || dateString.equalsIgnoreCase("null")) {
            return null;
        }
        try {
            // Attempt parsing with the defined formatter first
            return LocalDateTime.parse(dateString, dateTimeFormatter);
        } catch (Exception e) {
            // Add fallback for common alternative formats if needed
            log.trace("Could not parse LocalDateTime: '{}' with format '{}'. Attempting fallbacks...", dateString, dateTimeFormatter.toString());
            // Example fallback: try parsing without milliseconds
            try {
                return LocalDateTime.parse(dateString, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            } catch (Exception e2) {
                // Example fallback: try parsing with 'T' separator
                try {
                    return LocalDateTime.parse(dateString, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                } catch (Exception e3) {
                    log.warn("Could not parse LocalDateTime: '{}' with any known format, returning null.", dateString);
                    return null;
                }
            }
        }
    }

    /**
     * Recursively inserts an entity and its parent if the parent doesn't exist.
     * @param <T> Entity type
     * @param <ID> ID type
     * @param entity Entity to insert
     * @param idGetter Function to get ID from entity
     * @param parentIdGetter Function to get parent ID from entity
     * @param parentGetter Function to get parent entity by ID (from the map)
     * @param entityClass Class of the entity
     * @param processedIds Set of already processed IDs to avoid duplicates and cycles
     */
    private <T, ID> void insertWithParentRecursively(
            T entity,
            java.util.function.Function<T, ID> idGetter,
            java.util.function.Function<T, ID> parentIdGetter,
            java.util.function.Function<ID, T> parentGetter, // Function to build parent from map data
            Class<T> entityClass,
            Set<ID> processedIds) {

        ID id = idGetter.apply(entity);

        // Skip if this ID has already been processed (prevents cycles) or exists in the database
        if (processedIds.contains(id) || DBUtils.entityExists(entityClass, id, entityManager)) {
            if (processedIds.contains(id)) {
                log.trace("Skipping already processed entity with ID: {}", id);
            } else {
                log.trace("Skipping entity already in DB with ID: {}", id);
            }
            return;
        }

        // Mark this ID as being processed *before* recursion
        processedIds.add(id);
        log.trace("Processing entity with ID: {}", id);

        ID parentId = parentIdGetter.apply(entity);

        if (parentId != null && !id.equals(parentId)) { // Check for self-reference
            // Check if parent exists in DB or is already processed
            if (!DBUtils.entityExists(entityClass, parentId, entityManager) && !processedIds.contains(parentId)) {
                // Parent doesn't exist and isn't processed yet - fetch and insert it first
                log.trace("Parent ID {} for entity ID {} not found in DB or processed set. Attempting recursive insert.", parentId, id);
                T parentEntity = parentGetter.apply(parentId); // Get parent data from the map
                if (parentEntity != null) {
                    insertWithParentRecursively(parentEntity, idGetter, parentIdGetter, parentGetter, entityClass, processedIds);
                } else {
                    // Parent ID exists in the current entity, but no corresponding row found in the CSV map.
                    // This indicates potential data inconsistency in the source file.
                    log.warn("Parent entity with ID {} for entity ID {} not found in the provided data map. Skipping parent insert.", parentId, id);
                    // Depending on FK constraints, saving the child might fail later.
                }
            } else {
                log.trace("Parent ID {} for entity ID {} already exists in DB or processed set.", parentId, id);
            }
        } else if (parentId != null && id.equals(parentId)) {
            log.warn("Detected self-reference for entity ID {}. Skipping parent processing.", id);
        }


        try {
            DBUtils.saveEntityWithForcedId(entity, entityManager);
            log.debug("Successfully saved entity with ID {}: {}", id, entity);
        } catch (Exception e) {
            // Catch specific exceptions if possible (e.g., ConstraintViolationException)
            log.error("Failed to save entity with ID {}: {}", id, entity, e);
            // Optionally remove from processedIds if save fails and retry logic is needed? Risky.
            // processedIds.remove(id);
        }
    }

    public boolean parseBoolean(String value) {
        // Handles "1", "0", "true", "false", case-insensitive, and null/empty
        if (value == null || value.trim().isEmpty() || value.equalsIgnoreCase("null")) {
            return false; // Default to false for null/empty/invalid, adjust as needed (check @ColumnDefault)
        }
        return value.equalsIgnoreCase("true") || value.equals("1");
    }

    // Helper to set common ActivableEntity fields if the mapping provides them
    // Added 'id' parameter for logging
    private void setActivableFields(ActivableEntity entity, CsvReader.CsvRow csvRow, Object legacyMapping, Object id) {
        try {
            if (legacyMapping instanceof ActivableLegacyMapping activableMapping) {
                // Check if mapping keys are present before accessing them
                if (activableMapping.getActive() != null) {
                    entity.setActive(parseBoolean(csvRow.get(activableMapping.getActive())));
                }else{
                    entity.setActive(true); // Default to true if not specified
                }
                if (activableMapping.getCreatedBy() != null) {
                    entity.setCreatedBy(csvRow.get(activableMapping.getCreatedBy()));
                }
                if (activableMapping.getCreatedDate() != null) {
                    entity.setCreatedDate(parseDateTime(csvRow.get(activableMapping.getCreatedDate())));
                }
                if (activableMapping.getLastModifiedBy() != null) {
                    entity.setLastModifiedBy(csvRow.get(activableMapping.getLastModifiedBy()));
                }
                if (activableMapping.getLastModifiedDate() != null) {
                    entity.setLastModifiedDate(parseDateTime(csvRow.get(activableMapping.getLastModifiedDate())));
                }
            }
        } catch (Exception e) {
            // Use the passed ID for logging
            log.warn("Could not set one or more activable fields for entity ID {}. Mapping or data might be missing/invalid.", id, e);
        }
    }

    // Helper to set common AuditedEntity fields if the mapping provides them
    // Added 'id' parameter for logging
    private void setAuditedFields(AuditedEntity entity, CsvReader.CsvRow csvRow, Object legacyMapping, Object id) {
        try {
            if (legacyMapping instanceof AuditedLegacyMapping auditedMapping) {
                // Check if mapping keys are present before accessing them
                if (auditedMapping.getCreatedBy() != null) {
                    entity.setCreatedBy(csvRow.get(auditedMapping.getCreatedBy()));
                }
                if (auditedMapping.getCreatedDate() != null) {
                    entity.setCreatedDate(parseDateTime(csvRow.get(auditedMapping.getCreatedDate())));
                }
                if (auditedMapping.getLastModifiedBy() != null) {
                    entity.setLastModifiedBy(csvRow.get(auditedMapping.getLastModifiedBy()));
                }
                if (auditedMapping.getLastModifiedDate() != null) {
                    entity.setLastModifiedDate(parseDateTime(csvRow.get(auditedMapping.getLastModifiedDate())));
                }
            }
        } catch (Exception e) {
            // Use the passed ID for logging
            log.warn("Could not set one or more audited fields for entity ID {}. Mapping or data might be missing/invalid.", id, e);
        }
    }
}