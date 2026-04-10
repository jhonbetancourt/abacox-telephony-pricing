package com.infomedia.abacox.telephonypricing.service.report;

import com.infomedia.abacox.telephonypricing.component.export.excel.ExcelGeneratorBuilder;
import com.infomedia.abacox.telephonypricing.component.modeltools.ModelConverter;
import com.infomedia.abacox.telephonypricing.component.utils.SortingUtils;
import com.infomedia.abacox.telephonypricing.db.repository.ReportRepository;
import com.infomedia.abacox.telephonypricing.dto.report.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Service
public class TelephonyUsageReportService {

        private final ReportRepository reportRepository;
        private final ModelConverter modelConverter;

        @Transactional(readOnly = true)
        public Slice<TelephonyTypeUsageGroupDto> generateTelephonyTypeUsageReport(
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

                SortingUtils.sort(groups, pageable.getSort(), Sort.by("categoryName"));

                if (pageable.isUnpaged()) {
                        return new SliceImpl<>(groups, pageable, false);
                }

                int start = (int) pageable.getOffset();
                int end = Math.min((start + pageable.getPageSize()), groups.size());

                List<TelephonyTypeUsageGroupDto> pageContent;
                if (start > groups.size()) {
                        pageContent = Collections.emptyList();
                } else {
                        pageContent = groups.subList(start, end);
                }

                boolean hasNext = end < groups.size();
                return new SliceImpl<>(pageContent, pageable, hasNext);
        }

        public void exportExcelTelephonyTypeUsageReport(
                        LocalDateTime startDate, LocalDateTime endDate,
                        OutputStream outputStream, ExcelGeneratorBuilder builder) {
                List<TelephonyTypeUsageGroupDto> allGroups = generateTelephonyTypeUsageReport(startDate, endDate,
                                Pageable.unpaged()).getContent();
                try {
                        builder.withFlattenedCollection("items")
                                        .generateStreaming(outputStream, (page, size) ->
                                                page == 0 ? allGroups : Collections.emptyList(),
                                        allGroups.size() + 1);
                } catch (IOException e) {
                        throw new RuntimeException(e);
                }
        }

        @Transactional(readOnly = true)
        public Slice<MonthlyTelephonyTypeUsageGroupDto> generateMonthlyTelephonyTypeUsageReport(
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

                SortingUtils.sort(groups, pageable.getSort(), Sort.by("telephonyTypeName"));

                if (pageable.isUnpaged()) {
                        return new SliceImpl<>(groups, pageable, false);
                }

                int start = (int) pageable.getOffset();
                int end = Math.min((start + pageable.getPageSize()), groups.size());

                List<MonthlyTelephonyTypeUsageGroupDto> pageContent;
                if (start > groups.size()) {
                        pageContent = Collections.emptyList();
                } else {
                        pageContent = groups.subList(start, end);
                }

                boolean hasNext = end < groups.size();
                return new SliceImpl<>(pageContent, pageable, hasNext);
        }

        public void exportExcelMonthlyTelephonyTypeUsageReport(
                        LocalDateTime startDate, LocalDateTime endDate,
                        OutputStream outputStream, ExcelGeneratorBuilder builder) {
                List<MonthlyTelephonyTypeUsageGroupDto> allGroups = generateMonthlyTelephonyTypeUsageReport(
                                startDate, endDate, Pageable.unpaged()).getContent();
                try {
                        builder.withFlattenedCollection("items")
                                        .generateStreaming(outputStream, (page, size) ->
                                                page == 0 ? allGroups : Collections.emptyList(),
                                        allGroups.size() + 1);
                } catch (IOException e) {
                        throw new RuntimeException(e);
                }
        }

        /**
         * Computes aggregate totals across all cost-center rows (including the synthetic
         * "unassigned" row, costCenterId == -1) for the given period. Used by dashboard KPIs.
         */
        @Transactional(readOnly = true)
        public CostCenterUsageTotalsDto getCostCenterUsageTotals(
                        LocalDateTime startDate, LocalDateTime endDate, Long parentCostCenterId) {
                List<CostCenterUsageReportDto> allRows = reportRepository
                                .getCostCenterUsageReport(startDate, endDate, parentCostCenterId, Pageable.unpaged())
                                .getContent()
                                .stream()
                                .map(row -> modelConverter.map(row, CostCenterUsageReportDto.class))
                                .collect(Collectors.toList());

                BigDecimal totalBilled = BigDecimal.ZERO;
                long totalDuration = 0L;
                long totalIncoming = 0L;
                long totalOutgoing = 0L;
                long unassigned = 0L;

                for (CostCenterUsageReportDto row : allRows) {
                        long inCalls  = row.getIncomingCallCount() != null ? row.getIncomingCallCount() : 0L;
                        long outCalls = row.getOutgoingCallCount() != null ? row.getOutgoingCallCount() : 0L;
                        totalBilled = totalBilled.add(row.getTotalBilledAmount() != null ? row.getTotalBilledAmount() : BigDecimal.ZERO);
                        totalDuration += row.getTotalDuration() != null ? row.getTotalDuration() : 0L;
                        totalIncoming += inCalls;
                        totalOutgoing += outCalls;
                        if (row.getCostCenterId() != null && row.getCostCenterId() == -1L) {
                                unassigned += inCalls + outCalls;
                        }
                }

                return CostCenterUsageTotalsDto.builder()
                                .totalBilledAmount(totalBilled)
                                .totalDurationSeconds(totalDuration)
                                .totalIncomingCalls(totalIncoming)
                                .totalOutgoingCalls(totalOutgoing)
                                .unassignedCalls(unassigned)
                                .build();
        }

        @Transactional(readOnly = true)
        public Slice<CostCenterUsageReportDto> generateCostCenterUsageReport(
                        LocalDateTime startDate, LocalDateTime endDate, Long parentCostCenterId, Pageable pageable) {
                List<CostCenterUsageReportDto> allRows = reportRepository
                                .getCostCenterUsageReport(startDate, endDate, parentCostCenterId, Pageable.unpaged())
                                .getContent()
                                .stream()
                                .map(row -> modelConverter.map(row, CostCenterUsageReportDto.class))
                                .collect(Collectors.toList());

                // Drop the synthetic unrelated/unassigned row (costCenterId == -1)
                List<CostCenterUsageReportDto> assignedRows = allRows.stream()
                                .filter(r -> r.getCostCenterId() == null || r.getCostCenterId() != -1L)
                                .collect(Collectors.toList());

                SortingUtils.sort(assignedRows, pageable.getSort(), Sort.by(Sort.Direction.DESC, "totalBilledAmount"));

                if (pageable.isUnpaged()) {
                        return new SliceImpl<>(assignedRows, pageable, false);
                }

                int start = (int) pageable.getOffset();
                int end = Math.min((start + pageable.getPageSize()), assignedRows.size());

                List<CostCenterUsageReportDto> pageContent;
                if (start > assignedRows.size()) {
                        pageContent = Collections.emptyList();
                } else {
                        pageContent = assignedRows.subList(start, end);
                }

                boolean hasNext = end < assignedRows.size();
                return new SliceImpl<>(pageContent, pageable, hasNext);
        }

        public void exportExcelCostCenterUsageReport(
                        LocalDateTime startDate, LocalDateTime endDate, Long parentCostCenterId,
                        OutputStream outputStream, ExcelGeneratorBuilder builder) {
                List<CostCenterUsageReportDto> allRows = generateCostCenterUsageReport(
                                startDate, endDate, parentCostCenterId, Pageable.unpaged()).getContent();
                try {
                        builder.excludeField("originCountryId")
                                        .generateStreaming(outputStream, (page, size) ->
                                                page == 0 ? allRows : Collections.emptyList(),
                                        allRows.size() + 1);
                } catch (IOException e) {
                        throw new RuntimeException(e);
                }
        }

        @Transactional(readOnly = true)
        public Slice<DialedNumberUsageReportDto> generateDialedNumberUsageReport(
                        LocalDateTime startDate, LocalDateTime endDate, Pageable pageable) {
                return modelConverter.mapSlice(
                                reportRepository.getDialedNumberUsageReport(startDate, endDate,
                                                SortingUtils.applyDefaultSort(pageable,
                                                                Sort.by(Sort.Order.desc("totalBilledAmount"), Sort.Order.desc("totalDuration")))),
                                DialedNumberUsageReportDto.class);
        }

        public void exportExcelDialedNumberUsageReport(
                        LocalDateTime startDate, LocalDateTime endDate, Sort sort, int maxRows,
                        OutputStream outputStream, ExcelGeneratorBuilder builder) {
                try {
                        builder.generateStreaming(outputStream, (page, size) ->
                                generateDialedNumberUsageReport(startDate, endDate,
                                        PageRequest.of(page, size, sort)).getContent(),
                                ExcelGeneratorBuilder.DEFAULT_STREAMING_PAGE_SIZE, maxRows);
                } catch (IOException e) {
                        throw new RuntimeException(e);
                }
        }

        @Transactional(readOnly = true)
        public Slice<DestinationUsageReportDto> generateDestinationUsageReport(
                        LocalDateTime startDate, LocalDateTime endDate, Pageable pageable) {
                return modelConverter.mapSlice(
                                reportRepository.getDestinationUsageReport(startDate, endDate,
                                                SortingUtils.applyDefaultSort(pageable,
                                                                Sort.by(Sort.Direction.DESC, "totalBilledAmount"))),
                                DestinationUsageReportDto.class);
        }

        public void exportExcelDestinationUsageReport(
                        LocalDateTime startDate, LocalDateTime endDate, Sort sort, int maxRows,
                        OutputStream outputStream, ExcelGeneratorBuilder builder) {
                try {
                        builder.generateStreaming(outputStream, (page, size) ->
                                generateDestinationUsageReport(startDate, endDate,
                                        PageRequest.of(page, size, sort)).getContent(),
                                ExcelGeneratorBuilder.DEFAULT_STREAMING_PAGE_SIZE, maxRows);
                } catch (IOException e) {
                        throw new RuntimeException(e);
                }
        }
}
