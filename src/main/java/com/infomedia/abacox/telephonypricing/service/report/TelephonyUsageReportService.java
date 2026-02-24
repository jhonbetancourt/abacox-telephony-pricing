package com.infomedia.abacox.telephonypricing.service.report;

import com.infomedia.abacox.telephonypricing.component.export.excel.ExcelGeneratorBuilder;
import com.infomedia.abacox.telephonypricing.component.modeltools.ModelConverter;
import com.infomedia.abacox.telephonypricing.db.repository.ReportRepository;
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
        public Page<CostCenterUsageReportDto> generateCostCenterUsageReport(
                        LocalDateTime startDate, LocalDateTime endDate, Pageable pageable) {
                return modelConverter.mapPage(reportRepository.getCostCenterUsageReport(startDate, endDate, pageable),
                                CostCenterUsageReportDto.class);
        }

        @Transactional(readOnly = true)
        public ByteArrayResource exportExcelCostCenterUsageReport(
                        LocalDateTime startDate, LocalDateTime endDate, Pageable pageable,
                        ExcelGeneratorBuilder builder) {
                Page<CostCenterUsageReportDto> collection = generateCostCenterUsageReport(startDate, endDate, pageable);
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
