package com.infomedia.abacox.telephonypricing.service.report;

import com.infomedia.abacox.telephonypricing.component.export.excel.ExcelGeneratorBuilder;
import com.infomedia.abacox.telephonypricing.component.modeltools.ModelConverter;
import com.infomedia.abacox.telephonypricing.db.repository.ReportRepository;
import com.infomedia.abacox.telephonypricing.dto.report.SubdivisionUsageReportDto;
import com.infomedia.abacox.telephonypricing.dto.report.SubdivisionUsageByTypeReportDto;
import com.infomedia.abacox.telephonypricing.dto.report.MonthlySubdivisionUsageReportDto;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;

@RequiredArgsConstructor
@Service
public class SubdivisionReportService {

    private final ReportRepository reportRepository;
    private final ModelConverter modelConverter;

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
}
