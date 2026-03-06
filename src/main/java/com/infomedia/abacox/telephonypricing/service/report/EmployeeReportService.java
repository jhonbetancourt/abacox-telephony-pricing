package com.infomedia.abacox.telephonypricing.service.report;

import com.infomedia.abacox.telephonypricing.component.export.excel.ExcelGeneratorBuilder;
import com.infomedia.abacox.telephonypricing.component.modeltools.ModelConverter;
import com.infomedia.abacox.telephonypricing.component.utils.SortingUtils;
import com.infomedia.abacox.telephonypricing.db.repository.ReportRepository;
import com.infomedia.abacox.telephonypricing.dto.report.EmployeeActivityReportDto;
import com.infomedia.abacox.telephonypricing.dto.report.EmployeeCallReportDto;
import com.infomedia.abacox.telephonypricing.dto.report.MissedCallEmployeeReportDto;
import com.infomedia.abacox.telephonypricing.dto.report.EmployeeAuthCodeUsageReportDto;
import com.infomedia.abacox.telephonypricing.dto.report.HighestConsumptionEmployeeReportDto;
import com.infomedia.abacox.telephonypricing.dto.report.TelephonyTypeCostDto;
import com.infomedia.abacox.telephonypricing.db.projection.EmployeeTelephonyTypeBreakdown;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Service
public class EmployeeReportService {

    private final ReportRepository reportRepository;
    private final ModelConverter modelConverter;

    @Transactional(readOnly = true)
    public Slice<EmployeeActivityReportDto> generateEmployeeActivityReport(String employeeName,
            String employeeExtension,
            LocalDateTime startDate, LocalDateTime endDate, Pageable pageable) {
        return modelConverter.mapSlice(reportRepository.getEmployeeActivityReport(startDate, endDate, employeeName,
                employeeExtension, SortingUtils.applyDefaultSort(pageable, Sort.by("extension"))),
                EmployeeActivityReportDto.class);
    }

    @Transactional(readOnly = true)
    public ByteArrayResource exportExcelEmployeeActivityReport(String employeeName, String employeeExtension,
            LocalDateTime startDate, LocalDateTime endDate, Pageable pageable, ExcelGeneratorBuilder builder) {
        Slice<EmployeeActivityReportDto> collection = generateEmployeeActivityReport(employeeName, employeeExtension,
                startDate, endDate, pageable);
        try {
            InputStream inputStream = builder.withEntities(collection.toList()).generateAsInputStream();
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Transactional(readOnly = true)
    public Slice<EmployeeCallReportDto> generateEmployeeCallReport(String employeeName, String employeeExtension,
            LocalDateTime startDate, LocalDateTime endDate, Pageable pageable) {
        Pageable effectivePageable = SortingUtils.applyDefaultSort(pageable,
                Sort.by(Sort.Order.desc("totalCost"), Sort.Order.asc("employeeName")));
        Slice<EmployeeCallReportDto> slice = modelConverter.mapSlice(
                reportRepository.getEmployeeCallReport(startDate, endDate, employeeName, employeeExtension,
                        effectivePageable),
                EmployeeCallReportDto.class);

        if (slice.isEmpty()) {
            return slice;
        }

        List<Long> employeeIds = slice.getContent().stream()
                .map(EmployeeCallReportDto::getEmployeeId)
                .collect(Collectors.toList());

        List<EmployeeTelephonyTypeBreakdown> breakdowns = reportRepository.getEmployeeTelephonyTypeBreakdown(startDate,
                endDate, employeeIds);

        Map<Long, List<TelephonyTypeCostDto>> breakdownMap = breakdowns.stream()
                .collect(Collectors.groupingBy(
                        EmployeeTelephonyTypeBreakdown::getEmployeeId,
                        Collectors.mapping(b -> new TelephonyTypeCostDto(b.getTelephonyTypeName(), b.getTotalCost()),
                                Collectors.toList())));

        slice.getContent().forEach(dto -> {
            dto.setTelephonyCosts(breakdownMap.getOrDefault(dto.getEmployeeId(), new ArrayList<>()));
        });

        return slice;
    }

    @Transactional(readOnly = true)
    public ByteArrayResource exportExcelEmployeeCallReport(String employeeName, String employeeExtension,
            LocalDateTime startDate, LocalDateTime endDate, Pageable pageable, ExcelGeneratorBuilder builder) {
        Slice<EmployeeCallReportDto> collection = generateEmployeeCallReport(employeeName, employeeExtension, startDate,
                endDate, pageable);
        try {
            InputStream inputStream = builder
                    .withEntities(collection.toList())
                    .withFlattenedCollection("telephonyCosts")
                    .generateAsInputStream();
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Transactional(readOnly = true)
    public Slice<MissedCallEmployeeReportDto> generateMissedCallEmployeeReport(String employeeName,
            LocalDateTime startDate,
            LocalDateTime endDate, Integer minRingCount, Pageable pageable) {
        return modelConverter.mapSlice(
                reportRepository.getMissedCallEmployeeReport(startDate, endDate, employeeName, minRingCount,
                        SortingUtils.applyDefaultSort(pageable, Sort.by(Sort.Direction.DESC, "missedCallCount"))),
                MissedCallEmployeeReportDto.class);
    }

    @Transactional(readOnly = true)
    public ByteArrayResource exportExcelMissedCallEmployeeReport(String employeeName, LocalDateTime startDate,
            LocalDateTime endDate, Integer minRingCount, Pageable pageable,
            ExcelGeneratorBuilder builder) {
        Slice<MissedCallEmployeeReportDto> collection = generateMissedCallEmployeeReport(employeeName, startDate,
                endDate, minRingCount, pageable);
        try {
            InputStream inputStream = builder.withEntities(collection.toList()).generateAsInputStream();
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Transactional(readOnly = true)
    public Slice<EmployeeAuthCodeUsageReportDto> generateEmployeeAuthCodeUsageReport(
            LocalDateTime startDate, LocalDateTime endDate, Pageable pageable) {
        return modelConverter.mapSlice(
                reportRepository.getEmployeeAuthCodeUsageReport(startDate, endDate,
                        SortingUtils.applyDefaultSort(pageable, Sort.by("employeeName"))),
                EmployeeAuthCodeUsageReportDto.class);
    }

    @Transactional(readOnly = true)
    public ByteArrayResource exportExcelEmployeeAuthCodeUsageReport(
            LocalDateTime startDate, LocalDateTime endDate, Pageable pageable, ExcelGeneratorBuilder builder) {
        Slice<EmployeeAuthCodeUsageReportDto> collection = generateEmployeeAuthCodeUsageReport(startDate, endDate,
                pageable);
        try {
            InputStream inputStream = builder.withEntities(collection.toList()).generateAsInputStream();
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Transactional(readOnly = true)
    public Slice<HighestConsumptionEmployeeReportDto> generateHighestConsumptionEmployeeReport(
            LocalDateTime startDate, LocalDateTime endDate, Pageable pageable) {
        return modelConverter.mapSlice(
                reportRepository.getHighestConsumptionEmployeeReport(startDate, endDate,
                        SortingUtils.applyDefaultSort(pageable, Sort.by(Sort.Direction.DESC, "totalBilledAmount"))),
                HighestConsumptionEmployeeReportDto.class);
    }

    @Transactional(readOnly = true)
    public ByteArrayResource exportExcelHighestConsumptionEmployeeReport(
            LocalDateTime startDate, LocalDateTime endDate, Pageable pageable, ExcelGeneratorBuilder builder) {
        Slice<HighestConsumptionEmployeeReportDto> collection = generateHighestConsumptionEmployeeReport(startDate,
                endDate, pageable);
        try {
            InputStream inputStream = builder.withEntities(collection.toList()).generateAsInputStream();
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
