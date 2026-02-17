package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.component.export.excel.ExcelGeneratorBuilder;
import com.infomedia.abacox.telephonypricing.component.modeltools.ModelConverter;
import com.infomedia.abacox.telephonypricing.db.entity.CallRecord;
import com.infomedia.abacox.telephonypricing.db.entity.FailedCallRecord;

import com.infomedia.abacox.telephonypricing.db.projection.ConferenceCandidateProjection;
import com.infomedia.abacox.telephonypricing.db.repository.*;
import com.infomedia.abacox.telephonypricing.db.view.CorporateReportView;
import com.infomedia.abacox.telephonypricing.dto.callrecord.CallRecordDto;
import com.infomedia.abacox.telephonypricing.dto.commlocation.CommLocationDto;
import com.infomedia.abacox.telephonypricing.dto.employee.EmployeeDto;
import com.infomedia.abacox.telephonypricing.dto.failedcallrecord.FailedCallRecordDto;
import com.infomedia.abacox.telephonypricing.dto.indicator.IndicatorDto;
import com.infomedia.abacox.telephonypricing.dto.operator.OperatorDto;
import com.infomedia.abacox.telephonypricing.dto.report.*;
import com.infomedia.abacox.telephonypricing.dto.telephonytype.TelephonyTypeDto;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Service
public class ReportService {

    private final ReportRepository reportRepository;
    private final CorporateReportViewRepository corporateReportViewRepository;
    private final FailedCallRecordRepository failedCallRecordRepository;
    private final CallRecordRepository callRecordRepository;
    private final ModelConverter modelConverter;

    private CallRecordDto callRecordDtoFromEntity(CallRecord entity) {
        if (entity == null) {
            return null;
        }

        CallRecordDto dto = new CallRecordDto();
        dto.setId(entity.getId());
        dto.setDial(entity.getDial());
        dto.setCommLocationId(entity.getCommLocationId());
        dto.setCommLocation(modelConverter.map(entity.getCommLocation(), CommLocationDto.class));
        dto.setServiceDate(entity.getServiceDate());
        dto.setOperatorId(entity.getOperatorId());
        dto.setOperator(modelConverter.map(entity.getOperator(), OperatorDto.class));
        dto.setEmployeeExtension(entity.getEmployeeExtension());
        dto.setEmployeeAuthCode(entity.getEmployeeAuthCode());
        dto.setIndicatorId(entity.getIndicatorId());
        dto.setIndicator(modelConverter.map(entity.getIndicator(), IndicatorDto.class));
        dto.setDestinationPhone(entity.getDestinationPhone());
        dto.setDuration(entity.getDuration());
        dto.setRingCount(entity.getRingCount());
        dto.setTelephonyTypeId(entity.getTelephonyTypeId());
        dto.setTelephonyType(modelConverter.map(entity.getTelephonyType(), TelephonyTypeDto.class));
        dto.setBilledAmount(entity.getBilledAmount());
        dto.setPricePerMinute(entity.getPricePerMinute());
        dto.setInitialPrice(entity.getInitialPrice());
        dto.setIncoming(entity.getIsIncoming());
        dto.setTrunk(entity.getTrunk());
        dto.setInitialTrunk(entity.getInitialTrunk());
        dto.setEmployeeId(entity.getEmployeeId());
        dto.setEmployee(modelConverter.map(entity.getEmployee(), EmployeeDto.class));
        dto.setEmployeeTransfer(entity.getEmployeeTransfer());
        dto.setTransferCause(entity.getTransferCause());
        dto.setAssignmentCause(entity.getAssignmentCause());
        dto.setDestinationEmployeeId(entity.getDestinationEmployeeId());
        dto.setDestinationEmployee(modelConverter.map(entity.getDestinationEmployee(), EmployeeDto.class));
        dto.setFileInfoId(entity.getFileInfoId());
        return dto;
    }

    @Transactional(readOnly = true)
    public Page<CallRecordDto> generateCallRecordsReport(Specification<CallRecord> specification, Pageable pageable) {
        Page<CallRecord> callRecords = callRecordRepository.findAll(specification, pageable);

        // Map entities to DTOs while preserving order
        List<CallRecordDto> dtos = callRecords.getContent()
                .stream()
                .map(this::callRecordDtoFromEntity)
                .collect(Collectors.toList());

        return new PageImpl<>(dtos, pageable, callRecords.getTotalElements());
    }

    @Transactional(readOnly = true)
    public ByteArrayResource exportExcelCallRecordsReport(Specification<CallRecord> specification, Pageable pageable,
            ExcelGeneratorBuilder builder) {
        Page<CallRecordDto> collection = generateCallRecordsReport(specification, pageable);
        try {
            InputStream inputStream = builder.withEntities(collection.toList()).generateAsInputStream();
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private FailedCallRecordDto failedCallRecordDtofromEntity(FailedCallRecord entity) {
        if (entity == null) {
            return null;
        }
        FailedCallRecordDto dto = new FailedCallRecordDto();
        dto.setId(entity.getId());
        dto.setEmployeeExtension(entity.getEmployeeExtension());
        dto.setErrorType(entity.getErrorType());
        dto.setErrorMessage(entity.getErrorMessage());
        dto.setOriginalCallRecordId(entity.getOriginalCallRecordId());
        dto.setProcessingStep(entity.getProcessingStep());
        dto.setFileInfoId(entity.getFileInfoId());
        dto.setCommLocationId(entity.getCommLocation() != null ? entity.getCommLocation().getId() : null);
        dto.setCommLocation(entity.getCommLocation() != null
                ? modelConverter.map(entity.getCommLocation(), CommLocationDto.class)
                : null);
        return dto;
    }

    @Transactional(readOnly = true)
    public Page<FailedCallRecordDto> generateFailedCallRecordsReport(Specification<FailedCallRecord> specification,
            Pageable pageable) {
        Page<FailedCallRecord> failedCallRecords = failedCallRecordRepository.findAll(specification, pageable);

        // Map entities to DTOs while preserving order
        List<FailedCallRecordDto> dtos = failedCallRecords.getContent()
                .stream()
                .map(this::failedCallRecordDtofromEntity)
                .toList();

        return new PageImpl<>(dtos, pageable, failedCallRecords.getTotalElements());
    }

    public ByteArrayResource exportExcelFailedCallRecordsReport(Specification<FailedCallRecord> specification,
            Pageable pageable, ExcelGeneratorBuilder builder) {
        Page<FailedCallRecordDto> collection = generateFailedCallRecordsReport(specification, pageable);
        try {
            InputStream inputStream = builder.withEntities(collection.toList()).generateAsInputStream();
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Transactional(readOnly = true)
    public Page<CorporateReportDto> generateCorporateReport(Specification<CorporateReportView> specification,
            Pageable pageable) {
        return modelConverter.mapPage(corporateReportViewRepository.findAll(specification, pageable),
                CorporateReportDto.class);
    }

    @Transactional(readOnly = true)
    public ByteArrayResource exportExcelCorporateReport(Specification<CorporateReportView> specification,
            Pageable pageable, ExcelGeneratorBuilder builder) {
        Page<CorporateReportDto> collection = generateCorporateReport(specification, pageable);
        try {
            InputStream inputStream = builder.withEntities(collection.toList()).generateAsInputStream();
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Transactional(readOnly = true)
    public Page<EmployeeActivityReportDto> generateEmployeeActivityReport(String employeeName, String employeeExtension,
            LocalDateTime startDate, LocalDateTime endDate, Pageable pageable) {
        return modelConverter.mapPage(reportRepository.getEmployeeActivityReport(startDate, endDate, employeeName,
                employeeExtension, pageable), EmployeeActivityReportDto.class);
    }

    @Transactional(readOnly = true)
    public ByteArrayResource exportExcelEmployeeActivityReport(String employeeName, String employeeExtension,
            LocalDateTime startDate, LocalDateTime endDate, Pageable pageable, ExcelGeneratorBuilder builder) {
        Page<EmployeeActivityReportDto> collection = generateEmployeeActivityReport(employeeName, employeeExtension,
                startDate, endDate, pageable);
        try {
            InputStream inputStream = builder.withEntities(collection.toList()).generateAsInputStream();
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Transactional(readOnly = true)
    public Page<EmployeeCallReportDto> generateEmployeeCallReport(String employeeName, String employeeExtension,
            LocalDateTime startDate, LocalDateTime endDate, Pageable pageable) {
        return modelConverter.mapPage(
                reportRepository.getEmployeeCallReport(startDate, endDate, employeeName, employeeExtension, pageable),
                EmployeeCallReportDto.class);
    }

    @Transactional(readOnly = true)
    public ByteArrayResource exportExcelEmployeeCallReport(String employeeName, String employeeExtension,
            LocalDateTime startDate, LocalDateTime endDate, Pageable pageable, ExcelGeneratorBuilder builder) {
        Page<EmployeeCallReportDto> collection = generateEmployeeCallReport(employeeName, employeeExtension, startDate,
                endDate, pageable);
        try {
            InputStream inputStream = builder.withEntities(collection.toList()).generateAsInputStream();
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Transactional(readOnly = true)
    public Page<UnassignedCallReportDto> generateUnassignedCallReport(String extension, LocalDateTime startDate,
            LocalDateTime endDate, Pageable pageable) {
        return modelConverter.mapPage(reportRepository.getUnassignedCallReport(startDate, endDate, extension, pageable),
                UnassignedCallReportDto.class);
    }

    @Transactional(readOnly = true)
    public ByteArrayResource exportExcelUnassignedCallReport(String extension, LocalDateTime startDate,
            LocalDateTime endDate, Pageable pageable, ExcelGeneratorBuilder builder) {
        Page<UnassignedCallReportDto> collection = generateUnassignedCallReport(extension, startDate, endDate,
                pageable);
        try {
            InputStream inputStream = builder.withEntities(collection.toList()).generateAsInputStream();
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Transactional(readOnly = true)
    public Page<ProcessingFailureReportDto> generateProcessingFailureReport(String directory, String errorType,
            LocalDateTime startDate, LocalDateTime endDate, Pageable pageable) {
        return modelConverter.mapPage(
                reportRepository.getProcessingFailureReport(startDate, endDate, directory, errorType, pageable),
                ProcessingFailureReportDto.class);
    }

    @Transactional(readOnly = true)
    public ByteArrayResource exportExcelProcessingFailureReport(String directory, String errorType,
            LocalDateTime startDate, LocalDateTime endDate, Pageable pageable, ExcelGeneratorBuilder builder) {
        Page<ProcessingFailureReportDto> collection = generateProcessingFailureReport(directory, errorType, startDate,
                endDate, pageable);
        try {
            InputStream inputStream = builder.withEntities(collection.toList()).generateAsInputStream();
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Transactional(readOnly = true)
    public Page<MissedCallEmployeeReportDto> generateMissedCallEmployeeReport(String employeeName,
            LocalDateTime startDate,
            LocalDateTime endDate, Pageable pageable) {
        return modelConverter.mapPage(
                reportRepository.getMissedCallEmployeeReport(startDate, endDate, employeeName, pageable),
                MissedCallEmployeeReportDto.class);
    }

    @Transactional(readOnly = true)
    public ByteArrayResource exportExcelMissedCallEmployeeReport(String employeeName, LocalDateTime startDate,
            LocalDateTime endDate, Pageable pageable,
            ExcelGeneratorBuilder builder) {
        Page<MissedCallEmployeeReportDto> collection = generateMissedCallEmployeeReport(employeeName, startDate,
                endDate, pageable);
        try {
            InputStream inputStream = builder.withEntities(collection.toList()).generateAsInputStream();
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Transactional(readOnly = true)
    public Page<UnusedExtensionReportDto> generateUnusedExtensionReport(String employeeName, String extension,
            LocalDateTime startDate, LocalDateTime endDate,
            Pageable pageable) {
        return modelConverter.mapPage(reportRepository.getUnusedExtensionReport(startDate, endDate, employeeName,
                extension, pageable), UnusedExtensionReportDto.class);
    }

    @Transactional(readOnly = true)
    public ByteArrayResource exportExcelUnusedExtensionReport(String employeeName, String extension,
            LocalDateTime startDate, LocalDateTime endDate,
            Pageable pageable, ExcelGeneratorBuilder builder) {
        Page<UnusedExtensionReportDto> collection = generateUnusedExtensionReport(employeeName, extension, startDate,
                endDate, pageable);
        try {
            InputStream inputStream = builder.withEntities(collection.toList()).generateAsInputStream();
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Transactional(readOnly = true)
    public Page<ExtensionGroupReportDto> generateExtensionGroupReport(LocalDateTime startDate, LocalDateTime endDate,
            List<String> extensions, List<Long> operatorIds,
            String voicemailNumber, Pageable pageable) {
        return modelConverter.mapPage(reportRepository.getExtensionGroupReport(startDate, endDate, extensions,
                operatorIds, voicemailNumber, pageable), ExtensionGroupReportDto.class);
    }

    @Transactional(readOnly = true)
    public ByteArrayResource exportExcelExtensionGroupReport(LocalDateTime startDate, LocalDateTime endDate,
            List<String> extensions, List<Long> operatorIds,
            String voicemailNumber, Pageable pageable,
            ExcelGeneratorBuilder builder) {
        Page<ExtensionGroupReportDto> collection = generateExtensionGroupReport(startDate, endDate, extensions,
                operatorIds, voicemailNumber, pageable);
        try {
            InputStream inputStream = builder.withEntities(collection.toList()).generateAsInputStream();
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Transactional(readOnly = true)
    public Page<SubdivisionUsageReportDto> generateSubdivisionUsageReport(
            LocalDateTime startDate, LocalDateTime endDate, List<Long> subdivisionIds, Pageable pageable) {
        return modelConverter.mapPage(
                reportRepository.getSubdivisionUsageReport(startDate, endDate, subdivisionIds, pageable),
                SubdivisionUsageReportDto.class);
    }

    @Transactional(readOnly = true)
    public ByteArrayResource exportExcelSubdivisionUsageReport(
            LocalDateTime startDate, LocalDateTime endDate, List<Long> subdivisionIds, Pageable pageable,
            ExcelGeneratorBuilder builder) {
        Page<SubdivisionUsageReportDto> collection = generateSubdivisionUsageReport(startDate, endDate, subdivisionIds,
                pageable);
        try {
            InputStream inputStream = builder.withEntities(collection.toList()).generateAsInputStream();
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Transactional(readOnly = true)
    public Page<SubdivisionUsageByTypeReportDto> generateSubdivisionUsageByTypeReport(
            LocalDateTime startDate, LocalDateTime endDate, List<Long> subdivisionIds, Pageable pageable) {
        return modelConverter.mapPage(
                reportRepository.getSubdivisionUsageByTypeReport(startDate, endDate, subdivisionIds, pageable),
                SubdivisionUsageByTypeReportDto.class);
    }

    @Transactional(readOnly = true)
    public ByteArrayResource exportExcelSubdivisionUsageByTypeReport(
            LocalDateTime startDate, LocalDateTime endDate, List<Long> subdivisionIds, Pageable pageable,
            ExcelGeneratorBuilder builder) {
        Page<SubdivisionUsageByTypeReportDto> collection = generateSubdivisionUsageByTypeReport(startDate, endDate,
                subdivisionIds, pageable);
        try {
            InputStream inputStream = builder.withEntities(collection.toList()).generateAsInputStream();
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Transactional(readOnly = true)
    public Page<TelephonyTypeUsageReportDto> generateTelephonyTypeUsageReport(
            LocalDateTime startDate, LocalDateTime endDate, Pageable pageable) {
        return modelConverter.mapPage(reportRepository.getTelephonyTypeUsageReport(startDate, endDate, pageable),
                TelephonyTypeUsageReportDto.class);
    }

    @Transactional(readOnly = true)
    public ByteArrayResource exportExcelTelephonyTypeUsageReport(
            LocalDateTime startDate, LocalDateTime endDate, Pageable pageable, ExcelGeneratorBuilder builder) {
        Page<TelephonyTypeUsageReportDto> collection = generateTelephonyTypeUsageReport(startDate, endDate, pageable);
        try {
            InputStream inputStream = builder.withEntities(collection.toList()).generateAsInputStream();
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Transactional(readOnly = true)
    public Page<MonthlyTelephonyTypeUsageReportDto> generateMonthlyTelephonyTypeUsageReport(
            LocalDateTime startDate, LocalDateTime endDate, Pageable pageable) {
        return modelConverter.mapPage(reportRepository.getMonthlyTelephonyTypeUsageReport(startDate, endDate, pageable),
                MonthlyTelephonyTypeUsageReportDto.class);
    }

    @Transactional(readOnly = true)
    public ByteArrayResource exportExcelMonthlyTelephonyTypeUsageReport(
            LocalDateTime startDate, LocalDateTime endDate, Pageable pageable, ExcelGeneratorBuilder builder) {
        Page<MonthlyTelephonyTypeUsageReportDto> collection = generateMonthlyTelephonyTypeUsageReport(startDate,
                endDate, pageable);
        try {
            InputStream inputStream = builder.withEntities(collection.toList()).generateAsInputStream();
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Transactional(readOnly = true)
    public Page<CostCenterUsageReportDto> generateCostCenterUsageReport(
            LocalDateTime startDate, LocalDateTime endDate, Pageable pageable) {
        return modelConverter.mapPage(reportRepository.getCostCenterUsageReport(startDate, endDate, pageable),
                CostCenterUsageReportDto.class);
    }

    @Transactional(readOnly = true)
    public ByteArrayResource exportExcelCostCenterUsageReport(
            LocalDateTime startDate, LocalDateTime endDate, Pageable pageable, ExcelGeneratorBuilder builder) {
        Page<CostCenterUsageReportDto> collection = generateCostCenterUsageReport(startDate, endDate, pageable);
        try {
            InputStream inputStream = builder.withEntities(collection.toList()).generateAsInputStream();
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Transactional(readOnly = true)
    public Page<EmployeeAuthCodeUsageReportDto> generateEmployeeAuthCodeUsageReport(
            LocalDateTime startDate, LocalDateTime endDate, Pageable pageable) {
        return modelConverter.mapPage(reportRepository.getEmployeeAuthCodeUsageReport(startDate, endDate, pageable),
                EmployeeAuthCodeUsageReportDto.class);
    }

    @Transactional(readOnly = true)
    public ByteArrayResource exportExcelEmployeeAuthCodeUsageReport(
            LocalDateTime startDate, LocalDateTime endDate, Pageable pageable, ExcelGeneratorBuilder builder) {
        Page<EmployeeAuthCodeUsageReportDto> collection = generateEmployeeAuthCodeUsageReport(startDate, endDate,
                pageable);
        try {
            InputStream inputStream = builder.withEntities(collection.toList()).generateAsInputStream();
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Transactional(readOnly = true)
    public Page<MonthlySubdivisionUsageReportDto> generateMonthlySubdivisionUsageReport(
            LocalDateTime startDate, LocalDateTime endDate, List<Long> subdivisionIds, Pageable pageable) {
        return modelConverter.mapPage(
                reportRepository.getMonthlySubdivisionUsageReport(startDate, endDate, subdivisionIds, pageable),
                MonthlySubdivisionUsageReportDto.class);
    }

    @Transactional(readOnly = true)
    public ByteArrayResource exportExcelMonthlySubdivisionUsageReport(
            LocalDateTime startDate, LocalDateTime endDate, List<Long> subdivisionIds, Pageable pageable,
            ExcelGeneratorBuilder builder) {
        Page<MonthlySubdivisionUsageReportDto> collection = generateMonthlySubdivisionUsageReport(startDate, endDate,
                subdivisionIds, pageable);
        try {
            InputStream inputStream = builder.withEntities(collection.toList()).generateAsInputStream();
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Transactional(readOnly = true)
    public Page<DialedNumberUsageReportDto> generateDialedNumberUsageReport(
            LocalDateTime startDate, LocalDateTime endDate, Pageable pageable) {
        return modelConverter.mapPage(reportRepository.getDialedNumberUsageReport(startDate, endDate, pageable),
                DialedNumberUsageReportDto.class);
    }

    @Transactional(readOnly = true)
    public ByteArrayResource exportExcelDialedNumberUsageReport(
            LocalDateTime startDate, LocalDateTime endDate, Pageable pageable, ExcelGeneratorBuilder builder) {
        Page<DialedNumberUsageReportDto> collection = generateDialedNumberUsageReport(startDate, endDate, pageable);
        try {
            InputStream inputStream = builder.withEntities(collection.toList()).generateAsInputStream();
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // In class ReportService

    @Transactional(readOnly = true)
    public Page<DestinationUsageReportDto> generateDestinationUsageReport(
            LocalDateTime startDate, LocalDateTime endDate, Pageable pageable) {
        return modelConverter.mapPage(reportRepository.getDestinationUsageReport(startDate, endDate, pageable),
                DestinationUsageReportDto.class);
    }

    @Transactional(readOnly = true)
    public ByteArrayResource exportExcelDestinationUsageReport(
            LocalDateTime startDate, LocalDateTime endDate, Pageable pageable, ExcelGeneratorBuilder builder) {
        Page<DestinationUsageReportDto> collection = generateDestinationUsageReport(startDate, endDate, pageable);
        try {
            InputStream inputStream = builder.withEntities(collection.toList()).generateAsInputStream();
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Transactional(readOnly = true)
    public Page<HighestConsumptionEmployeeReportDto> generateHighestConsumptionEmployeeReport(
            LocalDateTime startDate, LocalDateTime endDate, Pageable pageable) {
        return modelConverter.mapPage(
                reportRepository.getHighestConsumptionEmployeeReport(startDate, endDate, pageable),
                HighestConsumptionEmployeeReportDto.class);
    }

    @Transactional(readOnly = true)
    public ByteArrayResource exportExcelHighestConsumptionEmployeeReport(
            LocalDateTime startDate, LocalDateTime endDate, Pageable pageable, ExcelGeneratorBuilder builder) {
        Page<HighestConsumptionEmployeeReportDto> collection = generateHighestConsumptionEmployeeReport(startDate,
                endDate, pageable);
        try {
            InputStream inputStream = builder.withEntities(collection.toList()).generateAsInputStream();
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // --- Conference Calls Report (Two-Phase Grouped Approach) ---

    // --- Conference Calls Report (Java Grouping Approach) ---

    @Transactional(readOnly = true)
    public Page<ConferenceGroupDto> generateConferenceCallsReport(
            LocalDateTime startDate, LocalDateTime endDate,
            String extension, String employeeName,
            Pageable pageable) {

        // 1. Fetch ALL candidates sorted by TransferKey, Dial, Date
        List<ConferenceCandidateProjection> rows = fetchConferenceCandidates(startDate, endDate, extension,
                employeeName);

        // 2. Group candidates in Java using Active Window logic
        List<ConferenceGroupDto> allGroups = groupCandidates(rows);

        // Sort groups by service date descending (newest first)
        allGroups.sort(Comparator.comparing(ConferenceGroupDto::getConferenceServiceDate).reversed());

        // 3. Paginate the resulting groups list
        int start = (int) pageable.getOffset();
        int end = Math.min((start + pageable.getPageSize()), allGroups.size());

        List<ConferenceGroupDto> pageContent;
        if (start > allGroups.size()) {
            pageContent = Collections.emptyList();
        } else {
            pageContent = allGroups.subList(start, end);
        }

        return new PageImpl<>(pageContent, pageable, allGroups.size());
    }

    private List<ConferenceCandidateProjection> fetchConferenceCandidates(LocalDateTime startDate,
            LocalDateTime endDate,
            String extension, String employeeName) {
        return reportRepository.findConferenceCandidates(startDate, endDate, extension, employeeName);
    }

    private List<ConferenceGroupDto> groupCandidates(List<ConferenceCandidateProjection> rows) {
        List<ConferenceGroupDto> completedGroups = new ArrayList<>();
        if (rows == null || rows.isEmpty()) {
            return completedGroups;
        }

        // Active groups mapped by: TransferKey -> List of active GroupContext
        Map<String, List<GroupContext>> activeGroups = new HashMap<>();
        // Keep track of all created groups to return them later
        List<GroupContext> allContexts = new ArrayList<>();

        for (ConferenceCandidateProjection row : rows) {
            String transferKey = row.getTransferKey();
            Integer transferCause = row.getTransferCause();
            LocalDateTime serviceDate = row.getServiceDate(); // Start Time
            Integer duration = row.getDuration() != null ? row.getDuration() : 0;
            LocalDateTime rowEnd = serviceDate.plusSeconds(duration);

            // Determine Grouping Identity
            // Type 10 (ConfNow): Group by Room (Dial)
            // Type 3 (Conference): Group by Initiator (Extension)
            String groupingIdentity;
            if (transferCause != null && transferCause == 10) {
                groupingIdentity = row.getDialedNumber();
            } else {
                groupingIdentity = row.getEmployeeExtension();
            }

            // Find matching active group
            GroupContext matchedContext = null;
            List<GroupContext> candidates = activeGroups.computeIfAbsent(transferKey, k -> new ArrayList<>());

            for (GroupContext context : candidates) {
                // Check Identity Match
                if (Objects.equals(groupingIdentity, context.groupingIdentity)) {
                    // Check Time Overlap (Active Window)
                    // If Row starts before Context ends, it belongs to this group.
                    if (!serviceDate.isAfter(context.lastActiveTime)) {
                        matchedContext = context;
                        break;
                    }
                }
            }

            if (matchedContext != null) {
                // Add to existing group

                // FILTER TYPE 10: If it's a "Conference Now" call, and the participant
                // extension
                // matches the room (organizer), skip adding it to avoid duplication.
                if (transferCause != null && transferCause == 10 &&
                        Objects.equals(row.getEmployeeExtension(), matchedContext.group.getOrganizerExtension())) {
                    // Do nothing, just update window if needed
                } else {
                    addParticipantToGroup(matchedContext.group, row, transferCause);
                }

                // Extend window if needed
                if (rowEnd.isAfter(matchedContext.lastActiveTime)) {
                    matchedContext.lastActiveTime = rowEnd;
                }
            } else {
                // Start new group
                ConferenceGroupDto newGroup = createGroupFromRow(row, groupingIdentity, transferCause);
                GroupContext newContext = new GroupContext(newGroup, rowEnd, groupingIdentity);

                // For the very first record (Organizer), we might want to add it as a
                // participant
                // unless it is Type 10 and it's self-referential
                if (transferCause != null && transferCause == 10 &&
                        Objects.equals(row.getEmployeeExtension(), groupingIdentity)) {
                    // Type 10 Organizer record (Room calling itself?) -> Don't add as participant
                } else {
                    addParticipantToGroup(newGroup, row, transferCause);
                }

                candidates.add(newContext);
                allContexts.add(newContext);
            }
        }

        // Filter valid conferences (more than 1 participant) and extract DTOs
        // Note: For display purposes, we might want to show even single-participant
        // "conferences"
        // if that's what the data suggests, but PHP filtered count > 1.
        // PHP logic: if (count($info_row) > 1)
        return allContexts.stream()
                .map(ctx -> ctx.group)
                .filter(g -> g.getParticipantCount() > 1)
                .sorted(Comparator.comparing(ConferenceGroupDto::getConferenceServiceDate).reversed())
                .collect(Collectors.toList());
    }

    private ConferenceGroupDto createGroupFromRow(ConferenceCandidateProjection row, String groupingIdentity,
            Integer transferCause) {
        ConferenceGroupDto group = new ConferenceGroupDto();
        group.setTransferKey(row.getTransferKey());

        // Organizer Mapping
        if (transferCause != null && transferCause == 10) {
            // Type 10: Organizer is the Room (Dialed)
            // The Query joins 'org' on 'dial', so we use Organizer fields from projection
            group.setOrganizerExtension(row.getDialedNumber());
            group.setOrganizerId(row.getOrganizerId());
            group.setOrganizerName(row.getOrganizerName());
            group.setOrganizerSubdivisionId(row.getOrganizerSubdivisionId());
            group.setOrganizerSubdivisionName(row.getOrganizerSubdivisionName());
        } else {
            // Type 3: Organizer is the Initiator (Extension)
            // The Initiator is the internal employee, so we use Employee fields from
            // projection
            group.setOrganizerExtension(row.getEmployeeExtension());
            group.setOrganizerId(row.getEmployeeId());
            group.setOrganizerName(row.getEmployeeName());
            group.setOrganizerSubdivisionId(row.getSubdivisionId());
            group.setOrganizerSubdivisionName(row.getSubdivisionName());
        }

        group.setParticipantCount(0L);
        group.setTotalBilled(BigDecimal.ZERO);
        group.setConferenceServiceDate(row.getServiceDate());
        group.setParticipants(new ArrayList<>());
        return group;
    }

    private void addParticipantToGroup(ConferenceGroupDto group, ConferenceCandidateProjection row,
            Integer transferCause) {
        ConferenceCallsReportDto dto = modelConverter.map(row, ConferenceCallsReportDto.class);

        if (transferCause != null && transferCause == 10) {
            // Type 10 (Conference Now):
            // Organizer is the Room (Dial). Participant is the Caller (Extension).
            String originalExt = row.getEmployeeExtension();
            String originalDial = row.getDialedNumber();

            dto.setEmployeeExtension(originalDial); // Show Room as "Extension/Phone" column?
                                                    // No, based on PHP _invertir:
                                                    // room becomes the "base", caller becomes the "target"
            dto.setEmployeeExtension(originalExt); // Participant Extension
            dto.setDialedNumber(originalDial); // Room
        } else {
            // Type 3 (Standard Conference):
            // Organizer is the Initator (Extension). Participant is the Recipient (Dial).
            // We need to overwrite the DTO fields because modelConverter maps
            // getEmployeeName()
            // which is the initiator. For the Arrow row, we want the Recipient.

            dto.setEmployeeExtension(row.getDialedNumber());
            dto.setEmployeeId(row.getOrganizerId());
            dto.setEmployeeName(row.getOrganizerName());
            dto.setSubdivisionId(row.getOrganizerSubdivisionId());
            dto.setSubdivisionName(row.getOrganizerSubdivisionName());
        }

        group.getParticipants().add(dto);
        group.setParticipantCount(group.getParticipantCount() + 1L);
        if (dto.getBilledAmount() != null) {
            group.setTotalBilled(group.getTotalBilled().add(dto.getBilledAmount()));
        }
    }

    private static class GroupContext {
        ConferenceGroupDto group;
        LocalDateTime lastActiveTime;
        String groupingIdentity;

        GroupContext(ConferenceGroupDto group, LocalDateTime lastActiveTime, String groupingIdentity) {
            this.group = group;
            this.lastActiveTime = lastActiveTime;
            this.groupingIdentity = groupingIdentity;
        }
    }

    @Transactional(readOnly = true)
    public ByteArrayResource exportExcelConferenceCallsReport(
            LocalDateTime startDate, LocalDateTime endDate,
            String extension, String employeeName,
            Pageable pageable, ExcelGeneratorBuilder builder) {
        Page<ConferenceGroupDto> collection = generateConferenceCallsReport(
                startDate, endDate, extension, employeeName, pageable);
        try {
            InputStream inputStream = builder
                    .withEntities(collection.toList())
                    .withFlattenedCollection("participants")
                    .withIncludedFields(
                            // Parent fields
                            "transferKey", "conferenceServiceDate", "participantCount", "totalBilled",
                            "organizerId", "organizerName", "organizerExtension", "organizerSubdivisionId",
                            "organizerSubdivisionName",
                            // Child fields
                            "participants.serviceDate", "participants.employeeExtension", "participants.duration",
                            "participants.billedAmount", "participants.employeeAuthCode", "participants.employeeName",
                            "participants.subdivisionName", "participants.telephonyTypeName",
                            "participants.contactName",
                            "participants.companyName")
                    .generateAsInputStream();
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}