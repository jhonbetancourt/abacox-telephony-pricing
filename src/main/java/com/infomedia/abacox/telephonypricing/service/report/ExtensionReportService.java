package com.infomedia.abacox.telephonypricing.service.report;

import com.infomedia.abacox.telephonypricing.component.export.excel.ExcelGeneratorBuilder;
import com.infomedia.abacox.telephonypricing.component.modeltools.ModelConverter;
import com.infomedia.abacox.telephonypricing.component.utils.InMemorySortUtils;
import com.infomedia.abacox.telephonypricing.db.repository.ReportRepository;
import com.infomedia.abacox.telephonypricing.dto.report.UnusedExtensionReportDto;
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

@RequiredArgsConstructor
@Service
public class ExtensionReportService {

    private final ReportRepository reportRepository;
    private final ModelConverter modelConverter;

    @Transactional(readOnly = true)
    public Slice<UnusedExtensionReportDto> generateUnusedExtensionReport(String employeeName, String extension,
            LocalDateTime startDate, LocalDateTime endDate,
            Pageable pageable) {
        return modelConverter.mapSlice(
                reportRepository.getUnusedExtensionReport(startDate, endDate, employeeName, extension,
                        InMemorySortUtils.applyDefaultSort(pageable, Sort.by("extension"))),
                UnusedExtensionReportDto.class);
    }

    @Transactional(readOnly = true)
    public ByteArrayResource exportExcelUnusedExtensionReport(String employeeName, String extension,
            LocalDateTime startDate, LocalDateTime endDate,
            Pageable pageable, ExcelGeneratorBuilder builder) {
        Slice<UnusedExtensionReportDto> collection = generateUnusedExtensionReport(employeeName, extension, startDate,
                endDate, pageable);
        try {
            InputStream inputStream = builder.withEntities(collection.toList()).generateAsInputStream();
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
