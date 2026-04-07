package com.infomedia.abacox.telephonypricing.service.report;

import com.infomedia.abacox.telephonypricing.component.cdrprocessing.EmployeeLookupService;
import com.infomedia.abacox.telephonypricing.component.export.excel.ExcelGeneratorBuilder;
import com.infomedia.abacox.telephonypricing.component.modeltools.ModelConverter;
import com.infomedia.abacox.telephonypricing.component.utils.SortingUtils;
import com.infomedia.abacox.telephonypricing.db.entity.CallRecord;
import com.infomedia.abacox.telephonypricing.db.entity.FailedCallRecord;

import com.infomedia.abacox.telephonypricing.db.repository.ReportRepository;
import com.infomedia.abacox.telephonypricing.db.repository.SliceableRepository;
import com.infomedia.abacox.telephonypricing.db.view.CorporateReportView;
import com.infomedia.abacox.telephonypricing.dto.callrecord.CallRecordDto;
import com.infomedia.abacox.telephonypricing.dto.commlocation.CommLocationDto;
import com.infomedia.abacox.telephonypricing.dto.employee.EmployeeDto;
import com.infomedia.abacox.telephonypricing.dto.failedcallrecord.FailedCallRecordDto;
import com.infomedia.abacox.telephonypricing.dto.indicator.IndicatorDto;
import com.infomedia.abacox.telephonypricing.dto.operator.OperatorDto;
import com.infomedia.abacox.telephonypricing.dto.report.CorporateReportDto;
import com.infomedia.abacox.telephonypricing.dto.report.ProcessingFailureReportDto;
import com.infomedia.abacox.telephonypricing.dto.report.UnassignedCallReportDto;
import com.infomedia.abacox.telephonypricing.dto.telephonytype.TelephonyTypeDto;
import com.infomedia.abacox.telephonypricing.model.report.UnassignedCallGroupingType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Service
public class CallRecordReportService {

    private final SliceableRepository sliceableRepository;
    private final ReportRepository reportRepository;
    private final ModelConverter modelConverter;
    private final EmployeeLookupService employeeLookupService;

    private CallRecordDto callRecordDtoFromEntity(CallRecord entity) {
        if (entity == null) {
            return null;
        }

        CallRecordDto dto = new CallRecordDto();
        dto.setId(entity.getId());
        dto.setDial(entity.getDial());
        dto.setCommLocationId(entity.getCommLocationId());
        dto.setCommLocation(
                entity.getCommLocation() != null ? modelConverter.map(entity.getCommLocation(), CommLocationDto.class)
                        : null);
        dto.setServiceDate(entity.getServiceDate());
        dto.setOperatorId(entity.getOperatorId());
        dto.setOperator(
                entity.getOperator() != null ? modelConverter.map(entity.getOperator(), OperatorDto.class) : null);
        dto.setEmployeeExtension(entity.getEmployeeExtension());
        dto.setEmployeeAuthCode(entity.getEmployeeAuthCode());
        dto.setIndicatorId(entity.getIndicatorId());
        dto.setIndicator(
                entity.getIndicator() != null ? modelConverter.map(entity.getIndicator(), IndicatorDto.class) : null);
        dto.setDestinationPhone(entity.getDestinationPhone());
        dto.setDuration(entity.getDuration());
        dto.setRingCount(entity.getRingCount());
        dto.setTelephonyTypeId(entity.getTelephonyTypeId());
        dto.setTelephonyType(entity.getTelephonyType() != null
                ? modelConverter.map(entity.getTelephonyType(), TelephonyTypeDto.class)
                : null);
        dto.setBilledAmount(entity.getBilledAmount());
        dto.setPricePerMinute(entity.getPricePerMinute());
        dto.setInitialPrice(entity.getInitialPrice());
        dto.setIncoming(entity.getIsIncoming());
        dto.setTrunk(entity.getTrunk());
        dto.setInitialTrunk(entity.getInitialTrunk());
        dto.setEmployeeId(entity.getEmployeeId());
        dto.setEmployee(
                entity.getEmployee() != null ? modelConverter.map(entity.getEmployee(), EmployeeDto.class) : null);
        dto.setEmployeeTransfer(entity.getEmployeeTransfer());
        dto.setTransferCause(entity.getTransferCause());
        dto.setAssignmentCause(entity.getAssignmentCause());
        dto.setDestinationEmployeeId(entity.getDestinationEmployeeId());
        dto.setDestinationEmployee(entity.getDestinationEmployee() != null
                ? modelConverter.map(entity.getDestinationEmployee(), EmployeeDto.class)
                : null);
        dto.setFileInfoId(entity.getFileInfoId());
        return dto;
    }

    @Transactional(readOnly = true)
    public Slice<CallRecordDto> generateCallRecordsReport(Specification<CallRecord> specification, Pageable pageable) {
        Slice<CallRecord> callRecords = sliceableRepository.findAllAsSlice(CallRecord.class, specification, pageable);

        List<CallRecordDto> dtos = callRecords.getContent()
                .stream()
                .map(this::callRecordDtoFromEntity)
                .collect(Collectors.toList());

        return new SliceImpl<>(dtos, pageable, callRecords.hasNext());
    }

    public void exportExcelCallRecordsReport(Specification<CallRecord> specification, Pageable pageable,
            OutputStream outputStream, ExcelGeneratorBuilder builder) {
        Sort sort = pageable.getSort();
        try {
            builder.generateStreaming(outputStream, (page, size) ->
                    generateCallRecordsReport(specification, PageRequest.of(page, size, sort)).getContent(),
                    ExcelGeneratorBuilder.DEFAULT_STREAMING_PAGE_SIZE);
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
        dto.setFileInfoId(entity.getFileInfoId());
        dto.setCommLocationId(entity.getCommLocation() != null ? entity.getCommLocation().getId() : null);
        dto.setCommLocation(entity.getCommLocation() != null
                ? modelConverter.map(entity.getCommLocation(), CommLocationDto.class)
                : null);
        return dto;
    }

    @Transactional(readOnly = true)
    public Slice<FailedCallRecordDto> generateFailedCallRecordsReport(Specification<FailedCallRecord> specification,
            Pageable pageable) {
        Slice<FailedCallRecord> failedCallRecords = sliceableRepository.findAllAsSlice(FailedCallRecord.class,
                specification, pageable);

        List<FailedCallRecordDto> dtos = failedCallRecords.getContent()
                .stream()
                .map(this::failedCallRecordDtofromEntity)
                .toList();

        return new SliceImpl<>(dtos, pageable, failedCallRecords.hasNext());
    }

    public void exportExcelFailedCallRecordsReport(Specification<FailedCallRecord> specification,
            Pageable pageable, OutputStream outputStream, ExcelGeneratorBuilder builder) {
        Sort sort = pageable.getSort();
        try {
            builder.generateStreaming(outputStream, (page, size) ->
                    generateFailedCallRecordsReport(specification, PageRequest.of(page, size, sort)).getContent(),
                    ExcelGeneratorBuilder.DEFAULT_STREAMING_PAGE_SIZE);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Transactional(readOnly = true)
    public Slice<CorporateReportDto> generateCorporateReport(Specification<CorporateReportView> specification,
            Pageable pageable) {
        return modelConverter.mapSlice(
                sliceableRepository.findAllAsSlice(CorporateReportView.class, specification, pageable),
                CorporateReportDto.class);
    }

    public void exportExcelCorporateReport(Specification<CorporateReportView> specification,
            Pageable pageable, OutputStream outputStream, ExcelGeneratorBuilder builder) {
        Sort sort = pageable.getSort();
        try {
            builder.generateStreaming(outputStream, (page, size) ->
                    generateCorporateReport(specification, PageRequest.of(page, size, sort)).getContent(),
                    ExcelGeneratorBuilder.DEFAULT_STREAMING_PAGE_SIZE);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Transactional(readOnly = true)
    public Slice<UnassignedCallReportDto> generateUnassignedCallReport(String extension,
            UnassignedCallGroupingType groupingType,
            LocalDateTime startDate, LocalDateTime endDate, Pageable pageable) {

        // 1. Get Extension Limits once
        var limitsMap = employeeLookupService.getExtensionLimits();

        // 2. Aggregate min/max digit-lengths across all active plants.
        // ExtensionLimits stores numeric range values (e.g. 100, 99999 for 3-5 digit
        // extensions).
        // We need the digit count (strlen equivalent), matching legacy:
        // strlen($arreglo['min']).
        int minLength = limitsMap.values().stream()
                .mapToInt(v -> String.valueOf(v.getMinLength()).length())
                .min()
                .orElse(3); // Fallback to 3 if no limits found

        int maxLength = limitsMap.values().stream()
                .mapToInt(v -> String.valueOf(v.getMaxLength()).length())
                .max()
                .orElse(5); // Fallback to 5 if no limits found

        // 3. Query repository with parameters
        return modelConverter.mapSlice(reportRepository.getUnassignedCallReport(
                startDate, endDate, extension, groupingType.name(), minLength, maxLength,
                SortingUtils.applyDefaultSort(pageable,
                        Sort.by(Sort.Order.desc("totalCost"), Sort.Order.desc("callCount"), Sort.Order.desc("lastCallDate")))),
                UnassignedCallReportDto.class);
    }

    public void exportExcelUnassignedCallReport(String extension, UnassignedCallGroupingType groupingType,
            LocalDateTime startDate, LocalDateTime endDate, Pageable pageable,
            OutputStream outputStream, ExcelGeneratorBuilder builder) {
        Sort sort = pageable.getSort();
        try {
            builder.generateStreaming(outputStream, (page, size) ->
                    generateUnassignedCallReport(extension, groupingType, startDate, endDate,
                            PageRequest.of(page, size, sort)).getContent(),
                    ExcelGeneratorBuilder.DEFAULT_STREAMING_PAGE_SIZE);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Transactional(readOnly = true)
    public Slice<ProcessingFailureReportDto> generateProcessingFailureReport(String directory, String errorType,
            LocalDateTime startDate, LocalDateTime endDate, Pageable pageable) {
        return modelConverter.mapSlice(
                reportRepository.getProcessingFailureReport(startDate, endDate, directory, errorType,
                        SortingUtils.applyDefaultSort(pageable,
                                Sort.by(Sort.Order.desc("failureCount"), Sort.Order.asc("commLocationDirectory"), Sort.Order.asc("plantTypeName")))),
                ProcessingFailureReportDto.class);
    }

    public void exportExcelProcessingFailureReport(String directory, String errorType,
            LocalDateTime startDate, LocalDateTime endDate, Pageable pageable,
            OutputStream outputStream, ExcelGeneratorBuilder builder) {
        Sort sort = pageable.getSort();
        try {
            builder.generateStreaming(outputStream, (page, size) ->
                    generateProcessingFailureReport(directory, errorType, startDate, endDate,
                            PageRequest.of(page, size, sort)).getContent(),
                    ExcelGeneratorBuilder.DEFAULT_STREAMING_PAGE_SIZE);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
