package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.component.export.excel.ExcelGeneratorBuilder;
import com.infomedia.abacox.telephonypricing.component.export.excel.ExcelGeneratorBuilder;
import com.infomedia.abacox.telephonypricing.component.modeltools.ModelConverter;
import com.infomedia.abacox.telephonypricing.db.projection.EmployeeActivityReport;
import com.infomedia.abacox.telephonypricing.db.projection.EmployeeCallReport;
import com.infomedia.abacox.telephonypricing.db.view.CorporateReportView;
import com.infomedia.abacox.telephonypricing.dto.callrecord.CorporateReportDto;
import com.infomedia.abacox.telephonypricing.dto.callrecord.EmployeeActivityReportDto;
import com.infomedia.abacox.telephonypricing.dto.employee.EmployeeCallReportDto;
import com.infomedia.abacox.telephonypricing.repository.ReportRepository;
import com.infomedia.abacox.telephonypricing.repository.view.CorporateReportViewRepository;
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
import java.util.Map;
import java.util.Set;

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
}
