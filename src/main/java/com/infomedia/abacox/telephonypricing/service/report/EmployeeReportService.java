package com.infomedia.abacox.telephonypricing.service.report;

import com.infomedia.abacox.telephonypricing.component.export.excel.ExcelGeneratorBuilder;
import com.infomedia.abacox.telephonypricing.component.modeltools.ModelConverter;
import com.infomedia.abacox.telephonypricing.db.repository.ReportRepository;
import com.infomedia.abacox.telephonypricing.dto.report.EmployeeActivityReportDto;
import com.infomedia.abacox.telephonypricing.dto.report.EmployeeCallReportDto;
import com.infomedia.abacox.telephonypricing.dto.report.MissedCallEmployeeReportDto;
import com.infomedia.abacox.telephonypricing.dto.report.EmployeeAuthCodeUsageReportDto;
import com.infomedia.abacox.telephonypricing.dto.report.HighestConsumptionEmployeeReportDto;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;

@RequiredArgsConstructor
@Service
public class EmployeeReportService {

    private final ReportRepository reportRepository;
    private final ModelConverter modelConverter;

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
}
