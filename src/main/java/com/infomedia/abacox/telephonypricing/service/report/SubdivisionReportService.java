package com.infomedia.abacox.telephonypricing.service.report;

import com.infomedia.abacox.telephonypricing.component.export.excel.ExcelGeneratorBuilder;
import com.infomedia.abacox.telephonypricing.component.modeltools.ModelConverter;
import com.infomedia.abacox.telephonypricing.component.utils.SortingUtils;
import com.infomedia.abacox.telephonypricing.db.projection.MonthlySubdivisionUsage;
import com.infomedia.abacox.telephonypricing.db.repository.ReportRepository;
import com.infomedia.abacox.telephonypricing.dto.report.MonthlyCostDto;
import com.infomedia.abacox.telephonypricing.dto.report.MonthlySubdivisionUsageReportDto;
import com.infomedia.abacox.telephonypricing.dto.report.SubdivisionUsageByTypeReportDto;
import com.infomedia.abacox.telephonypricing.dto.report.SubdivisionUsageReportDto;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.data.domain.Sort;
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
    public Slice<SubdivisionUsageReportDto> generateSubdivisionUsageReport(
            LocalDateTime startDate, LocalDateTime endDate, Long parentSubdivisionId, Pageable pageable) {
        return modelConverter.mapSlice(
                reportRepository.getSubdivisionUsageReport(startDate, endDate, parentSubdivisionId,
                        SortingUtils.applyDefaultSort(pageable, Sort.by("subdivisionName"))),
                SubdivisionUsageReportDto.class);
    }

    @Transactional(readOnly = true)
    public ByteArrayResource exportExcelSubdivisionUsageReport(
            LocalDateTime startDate, LocalDateTime endDate, Long parentSubdivisionId, Pageable pageable,
            ExcelGeneratorBuilder builder) {
        Slice<SubdivisionUsageReportDto> collection = generateSubdivisionUsageReport(startDate, endDate,
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
    public Slice<SubdivisionUsageByTypeReportDto> generateSubdivisionUsageByTypeReport(
            LocalDateTime startDate, LocalDateTime endDate, List<Long> subdivisionIds, Pageable pageable) {
        return modelConverter.mapSlice(
                reportRepository.getSubdivisionUsageByTypeReport(startDate, endDate, subdivisionIds,
                        SortingUtils.applyDefaultSort(pageable, Sort.by("subdivisionName"))),
                SubdivisionUsageByTypeReportDto.class);
    }

    @Transactional(readOnly = true)
    public ByteArrayResource exportExcelSubdivisionUsageByTypeReport(
            LocalDateTime startDate, LocalDateTime endDate, List<Long> subdivisionIds, Pageable pageable,
            ExcelGeneratorBuilder builder) {
        Slice<SubdivisionUsageByTypeReportDto> collection = generateSubdivisionUsageByTypeReport(startDate, endDate,
                subdivisionIds, pageable);
        try {
            InputStream inputStream = builder.withEntities(collection.toList()).generateAsInputStream();
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Transactional(readOnly = true)
    public Slice<MonthlySubdivisionUsageReportDto> generateMonthlySubdivisionUsageReport(
            LocalDateTime startDate, LocalDateTime endDate, List<Long> subdivisionIds, Pageable pageable) {

        List<MonthlySubdivisionUsage> rawList = reportRepository.getMonthlySubdivisionUsageReportAll(
                startDate, endDate, subdivisionIds);

        if (rawList.isEmpty()) {
            return new SliceImpl<>(Collections.emptyList(), pageable, false);
        }

        // Group the projections by subdivisionId
        Map<Long, List<MonthlySubdivisionUsage>> grouped = rawList.stream()
                .collect(Collectors.groupingBy(MonthlySubdivisionUsage::getSubdivisionId, LinkedHashMap::new,
                        Collectors.toList()));

        // Calculate the full range of months expected
        List<java.time.YearMonth> expectedMonths = new ArrayList<>();
        java.time.YearMonth currentYearMonth = java.time.YearMonth.from(startDate);
        java.time.YearMonth endYearMonth = java.time.YearMonth.from(endDate);
        while (!currentYearMonth.isAfter(endYearMonth)) {
            expectedMonths.add(currentYearMonth);
            currentYearMonth = currentYearMonth.plusMonths(1);
        }

        List<MonthlySubdivisionUsageReportDto> allDtos = new ArrayList<>();

        grouped.forEach((subdivisionId, projections) -> {
            MonthlySubdivisionUsage projection = projections.get(0);
            String subdivisionName = projection.getSubdivisionName();
            String csv = projection.getMonthlyCostsCsv();

            // Create a map of existing costs by parsing the aggregated CSV string
            Map<java.time.YearMonth, BigDecimal> costMap = new HashMap<>();
            if (csv != null && !csv.isBlank()) {
                String[] entries = csv.split(",");
                for (String entry : entries) {
                    String[] parts = entry.split(":");
                    if (parts.length == 3) {
                        try {
                            int year = Integer.parseInt(parts[0]);
                            int monthValue = Integer.parseInt(parts[1]);
                            BigDecimal cost = new BigDecimal(parts[2]);
                            costMap.put(java.time.YearMonth.of(year, monthValue), cost);
                        } catch (NumberFormatException e) {
                            // Log or handle parsing error if necessary
                        }
                    }
                }
            }

            // Fill gaps with ZERO for all expected months
            List<MonthlyCostDto> monthlyCosts = expectedMonths.stream()
                    .map(ym -> MonthlyCostDto.builder()
                            .year(ym.getYear())
                            .month(ym.getMonthValue())
                            .cost(costMap.getOrDefault(ym, BigDecimal.ZERO))
                            .build())
                    .collect(Collectors.toList());

            // Calculate variation based on the full list of costs (including filled zeroes)
            BigDecimal totalVariation = calculateCumulativeVariation(
                    monthlyCosts.stream().map(MonthlyCostDto::getCost).collect(Collectors.toList()));

            allDtos.add(MonthlySubdivisionUsageReportDto.builder()
                    .subdivisionId(subdivisionId)
                    .subdivisionName(subdivisionName)
                    .monthlyCosts(monthlyCosts)
                    .totalVariation(totalVariation)
                    .build());
        });

        // Sort before abs conversion so signed values drive ordering (default: drops first, then increases)
        SortingUtils.sort(allDtos, pageable.getSort(),
                Sort.by("totalVariation").and(Sort.by("subdivisionName")));

        // Format variation as absolute value for display as requested ("always
        // positive")
        allDtos.forEach(dto -> dto.setTotalVariation(dto.getTotalVariation().abs()));

        // Manually apply paging using SliceImpl
        int start = (int) pageable.getOffset();
        int end = Math.min((start + pageable.getPageSize()), allDtos.size());

        List<MonthlySubdivisionUsageReportDto> pageContent = new ArrayList<>();
        if (start < allDtos.size()) {
            pageContent = allDtos.subList(start, end);
        }

        boolean hasNext = end < allDtos.size();
        return new SliceImpl<>(pageContent, pageable, hasNext);
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
        Slice<MonthlySubdivisionUsageReportDto> collection = generateMonthlySubdivisionUsageReport(startDate, endDate,
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
