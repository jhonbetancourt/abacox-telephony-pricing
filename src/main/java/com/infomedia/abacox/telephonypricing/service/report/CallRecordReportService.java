package com.infomedia.abacox.telephonypricing.service.report;

import com.infomedia.abacox.telephonypricing.component.export.excel.ExcelGeneratorBuilder;
import com.infomedia.abacox.telephonypricing.component.modeltools.ModelConverter;
import com.infomedia.abacox.telephonypricing.db.entity.CallRecord;
import com.infomedia.abacox.telephonypricing.db.entity.FailedCallRecord;
import com.infomedia.abacox.telephonypricing.db.repository.CallRecordRepository;
import com.infomedia.abacox.telephonypricing.db.repository.CorporateReportViewRepository;
import com.infomedia.abacox.telephonypricing.db.repository.FailedCallRecordRepository;
import com.infomedia.abacox.telephonypricing.db.repository.ReportRepository;
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
import java.util.List;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Service
public class CallRecordReportService {

    private final CallRecordRepository callRecordRepository;
    private final FailedCallRecordRepository failedCallRecordRepository;
    private final CorporateReportViewRepository corporateReportViewRepository;
    private final ReportRepository reportRepository;
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

        List<FailedCallRecordDto> dtos = failedCallRecords.getContent()
                .stream()
                .map(this::failedCallRecordDtofromEntity)
                .toList();

        return new PageImpl<>(dtos, pageable, failedCallRecords.getTotalElements());
    }

    @Transactional(readOnly = true)
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
    public Page<UnassignedCallReportDto> generateUnassignedCallReport(String extension, LocalDateTime startDate,
            LocalDateTime endDate, Pageable pageable) {
        return modelConverter.mapPage(reportRepository.getUnassignedCallReport(startDate, endDate, extension, pageable),
                UnassignedCallReportDto.class);
    }

    @Transactional(readOnly = true)
    public ByteArrayResource exportExcelUnassignedCallReport(String extension, LocalDateTime startDate,
            LocalDateTime endDate, Pageable pageable, ExcelGeneratorBuilder builder) {
        Page<UnassignedCallReportDto> collection = generateUnassignedCallReport(extension, startDate,
                endDate, pageable);
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
}
