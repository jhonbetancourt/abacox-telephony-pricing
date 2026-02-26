package com.infomedia.abacox.telephonypricing.service.report;

import com.infomedia.abacox.telephonypricing.component.export.excel.ExcelGeneratorBuilder;
import com.infomedia.abacox.telephonypricing.component.modeltools.ModelConverter;
import com.infomedia.abacox.telephonypricing.db.repository.ReportRepository;
import com.infomedia.abacox.telephonypricing.dto.generic.PageWithSummaries;
import com.infomedia.abacox.telephonypricing.dto.report.*;
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
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Service
public class TelephonyUsageReportService {

        private final ReportRepository reportRepository;
        private final ModelConverter modelConverter;

        @Transactional(readOnly = true)
        public Page<TelephonyTypeUsageGroupDto> generateTelephonyTypeUsageReport(
                        LocalDateTime startDate, LocalDateTime endDate, Pageable pageable) {

                List<com.infomedia.abacox.telephonypricing.db.projection.TelephonyTypeUsageReport> rows = reportRepository
                                .getTelephonyTypeUsageReport(startDate, endDate, Pageable.unpaged()).getContent();

                List<TelephonyTypeUsageReportDto> allDtoRows = rows.stream()
                                .map(row -> modelConverter.map(row, TelephonyTypeUsageReportDto.class))
                                .collect(Collectors.toList());

                Map<String, List<TelephonyTypeUsageReportDto>> grouped = allDtoRows.stream()
                                .collect(Collectors.groupingBy(
                                                dto -> dto.getTelephonyCategoryName() != null
                                                                ? dto.getTelephonyCategoryName()
                                                                : "Sin Categoría",
                                                LinkedHashMap::new,
                                                Collectors.toList()));

                List<TelephonyTypeUsageGroupDto> groups = new ArrayList<>();
                for (Map.Entry<String, List<TelephonyTypeUsageReportDto>> entry : grouped.entrySet()) {
                        String categoryName = entry.getKey();
                        List<TelephonyTypeUsageReportDto> items = entry.getValue();

                        TelephonyTypeUsageReportDto subtotal = new TelephonyTypeUsageReportDto();
                        subtotal.setTelephonyCategoryName(categoryName);
                        subtotal.setTelephonyTypeName("Subtotal");
                        subtotal.setIncomingCallCount(
                                        items.stream().mapToLong(TelephonyTypeUsageReportDto::getIncomingCallCount)
                                                        .sum());
                        subtotal.setOutgoingCallCount(
                                        items.stream().mapToLong(TelephonyTypeUsageReportDto::getOutgoingCallCount)
                                                        .sum());
                        subtotal.setTotalDuration(
                                        items.stream().mapToLong(TelephonyTypeUsageReportDto::getTotalDuration).sum());
                        subtotal.setTotalBilledAmount(items.stream()
                                        .map(TelephonyTypeUsageReportDto::getTotalBilledAmount)
                                        .filter(Objects::nonNull)
                                        .reduce(BigDecimal.ZERO, BigDecimal::add));

                        subtotal.setDurationPercentage(items.stream()
                                        .map(TelephonyTypeUsageReportDto::getDurationPercentage)
                                        .filter(Objects::nonNull)
                                        .reduce(BigDecimal.ZERO, BigDecimal::add));
                        subtotal.setBilledAmountPercentage(items.stream()
                                        .map(TelephonyTypeUsageReportDto::getBilledAmountPercentage)
                                        .filter(Objects::nonNull)
                                        .reduce(BigDecimal.ZERO, BigDecimal::add));

                        groups.add(new TelephonyTypeUsageGroupDto(categoryName, items, subtotal));
                }

                int start = (int) pageable.getOffset();
                int end = Math.min((start + pageable.getPageSize()), groups.size());

                List<TelephonyTypeUsageGroupDto> pageContent;
                if (start > groups.size()) {
                        pageContent = Collections.emptyList();
                } else {
                        pageContent = groups.subList(start, end);
                }

                return new PageImpl<>(pageContent, pageable, groups.size());
        }

        @Transactional(readOnly = true)
        public ByteArrayResource exportExcelTelephonyTypeUsageReport(
                        LocalDateTime startDate, LocalDateTime endDate, Pageable pageable,
                        ExcelGeneratorBuilder builder) {
                Page<TelephonyTypeUsageGroupDto> collection = generateTelephonyTypeUsageReport(startDate, endDate,
                                pageable);
                try {
                        InputStream inputStream = builder.withEntities(collection.toList())
                                        .withFlattenedCollection("items")
                                        .generateAsInputStream();
                        return new ByteArrayResource(inputStream.readAllBytes());
                } catch (IOException e) {
                        throw new RuntimeException(e);
                }
        }

        @Transactional(readOnly = true)
        public Page<MonthlyTelephonyTypeUsageGroupDto> generateMonthlyTelephonyTypeUsageReport(
                        LocalDateTime startDate, LocalDateTime endDate, Pageable pageable) {
                List<com.infomedia.abacox.telephonypricing.db.projection.MonthlyTelephonyTypeUsageReport> rows = reportRepository
                                .getMonthlyTelephonyTypeUsageReport(startDate, endDate, Pageable.unpaged())
                                .getContent();

                List<MonthlyTelephonyTypeUsageReportDto> allDtoRows = rows.stream()
                                .map(row -> modelConverter.map(row, MonthlyTelephonyTypeUsageReportDto.class))
                                .collect(Collectors.toList());

                Map<String, List<MonthlyTelephonyTypeUsageReportDto>> grouped = allDtoRows.stream()
                                .collect(Collectors.groupingBy(
                                                MonthlyTelephonyTypeUsageReportDto::getTelephonyTypeName,
                                                LinkedHashMap::new,
                                                Collectors.toList()));

                List<MonthlyTelephonyTypeUsageGroupDto> groups = new ArrayList<>();
                for (Map.Entry<String, List<MonthlyTelephonyTypeUsageReportDto>> entry : grouped.entrySet()) {
                        String typeName = entry.getKey();
                        List<MonthlyTelephonyTypeUsageReportDto> items = entry.getValue();

                        MonthlyTelephonyTypeUsageReportDto subtotal = new MonthlyTelephonyTypeUsageReportDto();
                        subtotal.setTelephonyTypeName(typeName);
                        subtotal.setIncomingCallCount(
                                        items.stream().mapToLong(
                                                        MonthlyTelephonyTypeUsageReportDto::getIncomingCallCount)
                                                        .sum());
                        subtotal.setOutgoingCallCount(
                                        items.stream().mapToLong(
                                                        MonthlyTelephonyTypeUsageReportDto::getOutgoingCallCount)
                                                        .sum());
                        subtotal.setTotalDuration(
                                        items.stream().mapToLong(MonthlyTelephonyTypeUsageReportDto::getTotalDuration)
                                                        .sum());
                        subtotal.setTotalBilledAmount(items.stream()
                                        .map(MonthlyTelephonyTypeUsageReportDto::getTotalBilledAmount)
                                        .filter(Objects::nonNull)
                                        .reduce(BigDecimal.ZERO, BigDecimal::add));

                        subtotal.setDurationPercentage(items.stream()
                                        .map(MonthlyTelephonyTypeUsageReportDto::getDurationPercentage)
                                        .filter(Objects::nonNull)
                                        .reduce(BigDecimal.ZERO, BigDecimal::add));
                        subtotal.setBilledAmountPercentage(items.stream()
                                        .map(MonthlyTelephonyTypeUsageReportDto::getBilledAmountPercentage)
                                        .filter(Objects::nonNull)
                                        .reduce(BigDecimal.ZERO, BigDecimal::add));

                        groups.add(new MonthlyTelephonyTypeUsageGroupDto(typeName, items, subtotal));
                }

                int start = (int) pageable.getOffset();
                int end = Math.min((start + pageable.getPageSize()), groups.size());

                List<MonthlyTelephonyTypeUsageGroupDto> pageContent;
                if (start > groups.size()) {
                        pageContent = Collections.emptyList();
                } else {
                        pageContent = groups.subList(start, end);
                }

                return new PageImpl<>(pageContent, pageable, groups.size());
        }

        @Transactional(readOnly = true)
        public ByteArrayResource exportExcelMonthlyTelephonyTypeUsageReport(
                        LocalDateTime startDate, LocalDateTime endDate, Pageable pageable,
                        ExcelGeneratorBuilder builder) {
                Page<MonthlyTelephonyTypeUsageGroupDto> collection = generateMonthlyTelephonyTypeUsageReport(
                                startDate,
                                endDate, pageable);
                try {
                        InputStream inputStream = builder.withEntities(collection.toList())
                                        .withFlattenedCollection("items")
                                        .generateAsInputStream();
                        return new ByteArrayResource(inputStream.readAllBytes());
                } catch (IOException e) {
                        throw new RuntimeException(e);
                }
        }

        @Transactional(readOnly = true)
        public PageWithSummaries<CostCenterUsageReportDto, UsageReportSummaryDto> generateCostCenterUsageReport(
                        LocalDateTime startDate, LocalDateTime endDate, Long parentCostCenterId, Pageable pageable) {
                // Fetch all rows unpaged to compute summaries
                List<CostCenterUsageReportDto> allRows = reportRepository
                                .getCostCenterUsageReport(startDate, endDate, parentCostCenterId, Pageable.unpaged())
                                .getContent()
                                .stream()
                                .map(row -> modelConverter.map(row, CostCenterUsageReportDto.class))
                                .collect(Collectors.toList());

                // Separate assigned rows and unrelated info row
                List<CostCenterUsageReportDto> assignedRows = allRows.stream()
                                .filter(r -> r.getCostCenterId() == null || r.getCostCenterId() != -1L)
                                .collect(Collectors.toList());

                CostCenterUsageReportDto unrelatedRow = allRows.stream()
                                .filter(r -> r.getCostCenterId() != null && r.getCostCenterId() == -1L)
                                .findFirst()
                                .orElse(null);

                // Calculate grand totals for accurate percentage calculation in summaries
                BigDecimal grandTotalBilled = allRows.stream()
                                .map(r -> r.getTotalBilledAmount() != null ? r.getTotalBilledAmount() : BigDecimal.ZERO)
                                .reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal grandTotalDuration = allRows.stream()
                                .map(r -> r.getTotalDuration() != null ? BigDecimal.valueOf(r.getTotalDuration())
                                                : BigDecimal.ZERO)
                                .reduce(BigDecimal.ZERO, BigDecimal::add);

                // Build summaries list
                List<UsageReportSummaryDto> summaries = new ArrayList<>();

                // Helper to calculate percentages consistently
                BiFunction<BigDecimal, BigDecimal, BigDecimal> calcPercent = (val, tot) -> {
                        if (tot.compareTo(BigDecimal.ZERO) == 0)
                                return BigDecimal.ZERO;
                        return val.multiply(new java.math.BigDecimal("100")).divide(tot, 2,
                                        java.math.RoundingMode.HALF_UP);
                };

                // "Subtotal (Assigned Calls)" summary row
                BigDecimal subtotalBilled = assignedRows.stream()
                                .map(r -> r.getTotalBilledAmount() != null ? r.getTotalBilledAmount() : BigDecimal.ZERO)
                                .reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal subtotalDuration = assignedRows.stream()
                                .map(r -> r.getTotalDuration() != null ? BigDecimal.valueOf(r.getTotalDuration())
                                                : BigDecimal.ZERO)
                                .reduce(BigDecimal.ZERO, BigDecimal::add);

                UsageReportSummaryDto subtotalRow = UsageReportSummaryDto.builder()
                                .rowType("SUBTOTAL")
                                .label("Subtotal (Assigned Calls)")
                                .incomingCallCount(assignedRows.stream().mapToLong(
                                                r -> r.getIncomingCallCount() != null ? r.getIncomingCallCount() : 0L)
                                                .sum())
                                .outgoingCallCount(assignedRows.stream().mapToLong(
                                                r -> r.getOutgoingCallCount() != null ? r.getOutgoingCallCount() : 0L)
                                                .sum())
                                .totalDuration(subtotalDuration.longValue())
                                .totalBilledAmount(subtotalBilled)
                                .durationPercentage(calcPercent.apply(subtotalDuration, grandTotalDuration))
                                .billedAmountPercentage(calcPercent.apply(subtotalBilled, grandTotalBilled))
                                .build();
                summaries.add(subtotalRow);

                // "Unassigned Information" row (always present)
                BigDecimal unrelatedBilled = unrelatedRow != null ? unrelatedRow.getTotalBilledAmount()
                                : BigDecimal.ZERO;
                BigDecimal unrelatedDuration = unrelatedRow != null && unrelatedRow.getTotalDuration() != null
                                ? BigDecimal.valueOf(unrelatedRow.getTotalDuration())
                                : BigDecimal.ZERO;

                UsageReportSummaryDto unrelatedSummary = UsageReportSummaryDto.builder()
                                .rowType("UNASSIGNED")
                                .label("Unassigned Information")
                                .incomingCallCount(unrelatedRow != null ? unrelatedRow.getIncomingCallCount() : 0L)
                                .outgoingCallCount(unrelatedRow != null ? unrelatedRow.getOutgoingCallCount() : 0L)
                                .totalDuration(unrelatedDuration.longValue())
                                .totalBilledAmount(unrelatedBilled)
                                .durationPercentage(calcPercent.apply(unrelatedDuration, grandTotalDuration))
                                .billedAmountPercentage(calcPercent.apply(unrelatedBilled, grandTotalBilled))
                                .build();
                summaries.add(unrelatedSummary);

                // Paginate only the assigned rows as content
                int start = (int) pageable.getOffset();
                int end = Math.min((start + pageable.getPageSize()), assignedRows.size());

                List<CostCenterUsageReportDto> pageContent;
                if (start > assignedRows.size()) {
                        pageContent = Collections.emptyList();
                } else {
                        pageContent = assignedRows.subList(start, end);
                }

                Page<CostCenterUsageReportDto> page = new PageImpl<>(pageContent, pageable, assignedRows.size());
                return PageWithSummaries.of(page, summaries);
        }

        @Transactional(readOnly = true)
        public ByteArrayResource exportExcelCostCenterUsageReport(
                        LocalDateTime startDate, LocalDateTime endDate, Long parentCostCenterId, Pageable pageable,
                        ExcelGeneratorBuilder builder) {
                PageWithSummaries<CostCenterUsageReportDto, UsageReportSummaryDto> reportPage = generateCostCenterUsageReport(
                                startDate,
                                endDate,
                                parentCostCenterId,
                                pageable);

                try {
                        InputStream inputStream = builder
                                        .withEntities(reportPage.getContent())
                                        .excludeField("originCountryId")
                                        .generateAsInputStream();
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
                        LocalDateTime startDate, LocalDateTime endDate, Pageable pageable,
                        ExcelGeneratorBuilder builder) {
                Page<DialedNumberUsageReportDto> collection = generateDialedNumberUsageReport(startDate, endDate,
                                pageable);
                try {
                        InputStream inputStream = builder.withEntities(collection.toList()).generateAsInputStream();
                        return new ByteArrayResource(inputStream.readAllBytes());
                } catch (IOException e) {
                        throw new RuntimeException(e);
                }
        }

        @Transactional(readOnly = true)
        public Page<DestinationUsageReportDto> generateDestinationUsageReport(
                        LocalDateTime startDate, LocalDateTime endDate, Pageable pageable) {
                return modelConverter.mapPage(reportRepository.getDestinationUsageReport(startDate, endDate, pageable),
                                DestinationUsageReportDto.class);
        }

        @Transactional(readOnly = true)
        public ByteArrayResource exportExcelDestinationUsageReport(
                        LocalDateTime startDate, LocalDateTime endDate, Pageable pageable,
                        ExcelGeneratorBuilder builder) {
                Page<DestinationUsageReportDto> collection = generateDestinationUsageReport(startDate, endDate,
                                pageable);
                try {
                        InputStream inputStream = builder.withEntities(collection.toList()).generateAsInputStream();
                        return new ByteArrayResource(inputStream.readAllBytes());
                } catch (IOException e) {
                        throw new RuntimeException(e);
                }
        }
}
