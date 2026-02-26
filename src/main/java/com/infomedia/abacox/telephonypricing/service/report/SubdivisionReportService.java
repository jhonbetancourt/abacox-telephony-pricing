package com.infomedia.abacox.telephonypricing.service.report;

import com.infomedia.abacox.telephonypricing.component.export.excel.ExcelGeneratorBuilder;
import com.infomedia.abacox.telephonypricing.component.modeltools.ModelConverter;
import com.infomedia.abacox.telephonypricing.db.projection.MonthlySubdivisionUsage;
import com.infomedia.abacox.telephonypricing.db.repository.ReportRepository;
import com.infomedia.abacox.telephonypricing.dto.report.MonthlyCostDto;
import com.infomedia.abacox.telephonypricing.dto.report.MonthlySubdivisionUsageReportDto;
import com.infomedia.abacox.telephonypricing.dto.report.SubdivisionUsageByTypeReportDto;
import com.infomedia.abacox.telephonypricing.dto.report.SubdivisionUsageReportDto;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Service
public class SubdivisionReportService {

    private final ReportRepository reportRepository;
    private final ModelConverter modelConverter;

    @Transactional(readOnly = true)
    public Page<SubdivisionUsageReportDto> generateSubdivisionUsageReport(
            LocalDateTime startDate, LocalDateTime endDate, Long parentSubdivisionId, Pageable pageable) {
        return modelConverter.mapPage(
                reportRepository.getSubdivisionUsageReport(startDate, endDate, parentSubdivisionId, pageable),
                SubdivisionUsageReportDto.class);
    }

    @Transactional(readOnly = true)
    public ByteArrayResource exportExcelSubdivisionUsageReport(
            LocalDateTime startDate, LocalDateTime endDate, Long parentSubdivisionId, Pageable pageable,
            ExcelGeneratorBuilder builder) {
        Page<SubdivisionUsageReportDto> collection = generateSubdivisionUsageReport(startDate, endDate,
                parentSubdivisionId,
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

        Page<MonthlySubdivisionUsage> rawPage = reportRepository.getMonthlySubdivisionUsageReport(
                startDate, endDate, subdivisionIds, pageable);

        if (rawPage.isEmpty()) {
            return Page.empty(pageable);
        }

        // Group the projections by subdivisionId focusing on the order from the
        // repository
        Map<Long, List<MonthlySubdivisionUsage>> grouped = rawPage.getContent().stream()
                .collect(Collectors.groupingBy(MonthlySubdivisionUsage::getSubdivisionId, LinkedHashMap::new,
                        Collectors.toList()));

        List<MonthlySubdivisionUsageReportDto> dtos = new ArrayList<>();

        grouped.forEach((subdivisionId, projections) -> {
            String subdivisionName = projections.get(0).getSubdivisionName();

            // Filter out projections with null year/month (subdivisions with no calls)
            List<MonthlySubdivisionUsage> validProjections = projections.stream()
                    .filter(p -> p.getYear() != null && p.getMonth() != null)
                    .collect(Collectors.toList());

            // Skip subdivisions with no valid data
            if (validProjections.isEmpty()) {
                return;
            }

            // Sort projections by year/month to ensure correct order for variation
            // calculation
            validProjections.sort(Comparator.comparing(MonthlySubdivisionUsage::getYear)
                    .thenComparing(MonthlySubdivisionUsage::getMonth));

            List<MonthlyCostDto> monthlyCosts = validProjections.stream()
                    .map(p -> MonthlyCostDto.builder()
                            .year(p.getYear())
                            .month(p.getMonth())
                            .cost(p.getTotalBilledAmount())
                            .build())
                    .collect(Collectors.toList());

            BigDecimal totalVariation = calculateCumulativeVariation(
                    validProjections.stream().map(MonthlySubdivisionUsage::getTotalBilledAmount)
                            .collect(Collectors.toList()));

            dtos.add(MonthlySubdivisionUsageReportDto.builder()
                    .subdivisionId(subdivisionId)
                    .subdivisionName(subdivisionName)
                    .monthlyCosts(monthlyCosts)
                    .totalVariation(totalVariation)
                    .build());
        });

        // Sort by variation descending
        dtos.sort(Comparator.comparing(MonthlySubdivisionUsageReportDto::getTotalVariation).reversed());

        return new PageImpl<>(dtos, pageable, rawPage.getTotalElements());
    }

    /**
     * Matches PHP calcular_variacion in consumo_por_meses.php
     * sum(((T[t]/T[t-1]) - 1) * 100)
     */
    private BigDecimal calculateCumulativeVariation(List<BigDecimal> values) {
        if (values == null || values.size() < 2) {
            return BigDecimal.ZERO;
        }

        BigDecimal totalVar = BigDecimal.ZERO;
        for (int i = 0; i < values.size() - 1; i++) {
            BigDecimal current = values.get(i);
            BigDecimal next = values.get(i + 1);

            if (current.compareTo(BigDecimal.ZERO) == 0 && next.compareTo(BigDecimal.ZERO) == 0) {
                continue;
            } else if (current.compareTo(BigDecimal.ZERO) == 0 && next.compareTo(BigDecimal.ZERO) > 0) {
                totalVar = totalVar.add(BigDecimal.valueOf(100));
            } else if (current.compareTo(BigDecimal.ZERO) > 0 && next.compareTo(BigDecimal.ZERO) == 0) {
                totalVar = totalVar.add(BigDecimal.valueOf(-100));
            } else if (current.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal ratio = next.divide(current, 4, RoundingMode.HALF_UP);
                BigDecimal variation = ratio.subtract(BigDecimal.ONE).multiply(BigDecimal.valueOf(100));
                totalVar = totalVar.add(variation);
            }
        }
        return totalVar.setScale(2, RoundingMode.HALF_UP);
    }

    @Transactional(readOnly = true)
    public ByteArrayResource exportExcelMonthlySubdivisionUsageReport(
            LocalDateTime startDate, LocalDateTime endDate, List<Long> subdivisionIds, Pageable pageable,
            ExcelGeneratorBuilder builder) {
        Page<MonthlySubdivisionUsageReportDto> collection = generateMonthlySubdivisionUsageReport(startDate, endDate,
                subdivisionIds, pageable);
        try {
            InputStream inputStream = builder
                    .withEntities(collection.toList())
                    .withFlattenedCollection("monthlyCosts")
                    .generateAsInputStream();
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
