package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.component.export.excel.ExcelGeneratorBuilder;
import com.infomedia.abacox.telephonypricing.component.modeltools.ModelConverter;
import com.infomedia.abacox.telephonypricing.db.view.CorporateReportView;
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
    private final ModelConverter modelConverter;


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
}