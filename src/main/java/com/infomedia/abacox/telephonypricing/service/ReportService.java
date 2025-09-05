package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.component.export.excel.ExcelGeneratorBuilder;
import com.infomedia.abacox.telephonypricing.component.modeltools.ModelConverter;
import com.infomedia.abacox.telephonypricing.db.entity.FailedCallRecord;
import com.infomedia.abacox.telephonypricing.db.repository.ConferenceCallsReportViewRepository;
import com.infomedia.abacox.telephonypricing.db.repository.FailedCallRecordRepository;
import com.infomedia.abacox.telephonypricing.db.view.ConferenceCallsReportView;
import com.infomedia.abacox.telephonypricing.db.view.CorporateReportView;
import com.infomedia.abacox.telephonypricing.dto.failedcallrecord.FailedCallRecordDto;
import com.infomedia.abacox.telephonypricing.dto.report.*;
import com.infomedia.abacox.telephonypricing.db.repository.ReportRepository;
import com.infomedia.abacox.telephonypricing.db.repository.CorporateReportViewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;

@RequiredArgsConstructor
@Service
public class ReportService {


    private final ReportRepository reportRepository;
    private final CorporateReportViewRepository corporateReportViewRepository;
    private final ConferenceCallsReportViewRepository conferenceCallsReportViewRepository;
    private final FailedCallRecordRepository failedCallRecordRepository;
    private final ModelConverter modelConverter;

    @Transactional(readOnly = true)
    public Page<FailedCallRecordDto> generateFailedCallRecordsReport(Specification<FailedCallRecord> specification, Pageable pageable) {
        return modelConverter.mapPage(failedCallRecordRepository.findAll(specification, pageable), FailedCallRecordDto.class);
    }

    public ByteArrayResource exportExcelFailedCallRecordsReport(Specification<FailedCallRecord> specification
            , Pageable pageable, ExcelGeneratorBuilder builder) {
        Page<FailedCallRecordDto> collection = generateFailedCallRecordsReport(specification, pageable);
        try {
            InputStream inputStream = builder.withEntities(collection.toList()).generateAsInputStream();
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Transactional(readOnly = true)
    public Page<CorporateReportDto> generateCorporateReport(Specification<CorporateReportView> specification, Pageable pageable) {
        return modelConverter.mapPage(corporateReportViewRepository.findAll(specification, pageable), CorporateReportDto.class);
    }

    @Transactional(readOnly = true)
    public ByteArrayResource exportExcelCorporateReport(Specification<CorporateReportView> specification
            , Pageable pageable, ExcelGeneratorBuilder builder) {
        Page<CorporateReportDto> collection = generateCorporateReport(specification, pageable);
        try {
            InputStream inputStream = builder.withEntities(collection.toList()).generateAsInputStream();
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Transactional(readOnly = true)
    public Page<EmployeeActivityReportDto> generateEmployeeActivityReport(String employeeName, String employeeExtension
            , LocalDateTime startDate, LocalDateTime endDate, Pageable pageable) {
        return modelConverter.mapPage(reportRepository.getEmployeeActivityReport(startDate, endDate, employeeName
                , employeeExtension, pageable), EmployeeActivityReportDto.class);
    }

    @Transactional(readOnly = true)
    public ByteArrayResource exportExcelEmployeeActivityReport(String employeeName, String employeeExtension
            , LocalDateTime startDate, LocalDateTime endDate, Pageable pageable, ExcelGeneratorBuilder builder) {
        Page<EmployeeActivityReportDto> collection = generateEmployeeActivityReport(employeeName, employeeExtension
                , startDate, endDate, pageable);
        try {
            InputStream inputStream = builder.withEntities(collection.toList()).generateAsInputStream();
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Transactional(readOnly = true)
    public Page<EmployeeCallReportDto> generateEmployeeCallReport(String employeeName, String employeeExtension
            , LocalDateTime startDate, LocalDateTime endDate, Pageable pageable) {
        return modelConverter.mapPage(reportRepository.getEmployeeCallReport(startDate, endDate
                , employeeName, employeeExtension, pageable), EmployeeCallReportDto.class);
    }

    @Transactional(readOnly = true)
    public ByteArrayResource exportExcelEmployeeCallReport(String employeeName, String employeeExtension
            , LocalDateTime startDate, LocalDateTime endDate, Pageable pageable, ExcelGeneratorBuilder builder) {
        Page<EmployeeCallReportDto> collection = generateEmployeeCallReport(employeeName, employeeExtension
                , startDate, endDate, pageable);
        try {
            InputStream inputStream = builder.withEntities(collection.toList()).generateAsInputStream();
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Transactional(readOnly = true)
    public Page<UnassignedCallReportDto> generateUnassignedCallReport(String extension, LocalDateTime startDate
            , LocalDateTime endDate, Pageable pageable) {
        return modelConverter.mapPage(reportRepository.getUnassignedCallReport(startDate, endDate, extension, pageable)
                , UnassignedCallReportDto.class);
    }

    @Transactional(readOnly = true)
    public ByteArrayResource exportExcelUnassignedCallReport(String extension, LocalDateTime startDate
            , LocalDateTime endDate, Pageable pageable, ExcelGeneratorBuilder builder) {
        Page<UnassignedCallReportDto> collection = generateUnassignedCallReport(extension, startDate, endDate, pageable);
        try {
            InputStream inputStream = builder.withEntities(collection.toList()).generateAsInputStream();
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Transactional(readOnly = true)
    public Page<ProcessingFailureReportDto> generateProcessingFailureReport(String directory, String errorType
            , LocalDateTime startDate, LocalDateTime endDate, Pageable pageable) {
        return modelConverter.mapPage(reportRepository.getProcessingFailureReport(startDate, endDate, directory
                , errorType, pageable), ProcessingFailureReportDto.class);
    }

    @Transactional(readOnly = true)
    public ByteArrayResource exportExcelProcessingFailureReport(String directory, String errorType
            , LocalDateTime startDate, LocalDateTime endDate, Pageable pageable, ExcelGeneratorBuilder builder) {
        Page<ProcessingFailureReportDto> collection = generateProcessingFailureReport(directory, errorType
                , startDate, endDate, pageable);
        try {
            InputStream inputStream = builder.withEntities(collection.toList()).generateAsInputStream();
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Transactional(readOnly = true)
    public Page<MissedCallEmployeeReportDto> generateMissedCallEmployeeReport(String employeeName, LocalDateTime startDate,
                                                                              LocalDateTime endDate, Pageable pageable) {
        return modelConverter.mapPage(reportRepository.getMissedCallEmployeeReport(startDate, endDate, employeeName, pageable),
                MissedCallEmployeeReportDto.class);
    }

    @Transactional(readOnly = true)
    public ByteArrayResource exportExcelMissedCallEmployeeReport(String employeeName, LocalDateTime startDate,
                                                                 LocalDateTime endDate, Pageable pageable,
                                                                 ExcelGeneratorBuilder builder) {
        Page<MissedCallEmployeeReportDto> collection = generateMissedCallEmployeeReport(employeeName, startDate, endDate, pageable);
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
        return modelConverter.mapPage(reportRepository.getSubdivisionUsageReport(startDate, endDate, subdivisionIds, pageable),
                SubdivisionUsageReportDto.class);
    }

    @Transactional(readOnly = true)
    public ByteArrayResource exportExcelSubdivisionUsageReport(
            LocalDateTime startDate, LocalDateTime endDate, List<Long> subdivisionIds, Pageable pageable, ExcelGeneratorBuilder builder) {
        Page<SubdivisionUsageReportDto> collection = generateSubdivisionUsageReport(startDate, endDate, subdivisionIds, pageable);
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
        return modelConverter.mapPage(reportRepository.getSubdivisionUsageByTypeReport(startDate, endDate, subdivisionIds, pageable),
                SubdivisionUsageByTypeReportDto.class);
    }

    @Transactional(readOnly = true)
    public ByteArrayResource exportExcelSubdivisionUsageByTypeReport(
            LocalDateTime startDate, LocalDateTime endDate, List<Long> subdivisionIds, Pageable pageable, ExcelGeneratorBuilder builder) {
        Page<SubdivisionUsageByTypeReportDto> collection = generateSubdivisionUsageByTypeReport(startDate, endDate, subdivisionIds, pageable);
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
        Page<MonthlyTelephonyTypeUsageReportDto> collection = generateMonthlyTelephonyTypeUsageReport(startDate, endDate, pageable);
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
        Page<EmployeeAuthCodeUsageReportDto> collection = generateEmployeeAuthCodeUsageReport(startDate, endDate, pageable);
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
        return modelConverter.mapPage(reportRepository.getMonthlySubdivisionUsageReport(startDate, endDate, subdivisionIds, pageable),
                MonthlySubdivisionUsageReportDto.class);
    }

    @Transactional(readOnly = true)
    public ByteArrayResource exportExcelMonthlySubdivisionUsageReport(
            LocalDateTime startDate, LocalDateTime endDate, List<Long> subdivisionIds, Pageable pageable, ExcelGeneratorBuilder builder) {
        Page<MonthlySubdivisionUsageReportDto> collection = generateMonthlySubdivisionUsageReport(startDate, endDate, subdivisionIds, pageable);
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
        return modelConverter.mapPage(reportRepository.getHighestConsumptionEmployeeReport(startDate, endDate, pageable),
                HighestConsumptionEmployeeReportDto.class);
    }

    @Transactional(readOnly = true)
    public ByteArrayResource exportExcelHighestConsumptionEmployeeReport(
            LocalDateTime startDate, LocalDateTime endDate, Pageable pageable, ExcelGeneratorBuilder builder) {
        Page<HighestConsumptionEmployeeReportDto> collection = generateHighestConsumptionEmployeeReport(startDate, endDate, pageable);
        try {
            InputStream inputStream = builder.withEntities(collection.toList()).generateAsInputStream();
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // In class ReportService

    @Transactional(readOnly = true)
    public Page<ConferenceCallsReportDto> generateConferenceCallsReport(Specification<ConferenceCallsReportView> specification, Pageable pageable) {
        return modelConverter.mapPage(conferenceCallsReportViewRepository.findAll(specification, pageable), ConferenceCallsReportDto.class);
    }

    @Transactional(readOnly = true)
    public ByteArrayResource exportExcelConferenceCallsReport(Specification<ConferenceCallsReportView> specification
            , Pageable pageable, ExcelGeneratorBuilder builder) {
        Page<ConferenceCallsReportDto> collection = generateConferenceCallsReport(specification, pageable);
        try {
            InputStream inputStream = builder.withEntities(collection.toList()).generateAsInputStream();
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}