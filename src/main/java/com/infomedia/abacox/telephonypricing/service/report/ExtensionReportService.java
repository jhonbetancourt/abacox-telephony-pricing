package com.infomedia.abacox.telephonypricing.service.report;

import com.infomedia.abacox.telephonypricing.component.export.excel.ExcelGeneratorBuilder;
import com.infomedia.abacox.telephonypricing.component.modeltools.ModelConverter;
import com.infomedia.abacox.telephonypricing.component.utils.SortingUtils;
import com.infomedia.abacox.telephonypricing.db.repository.ReportRepository;
import com.infomedia.abacox.telephonypricing.dto.report.UnusedExtensionReportDto;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
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
                        SortingUtils.applyDefaultSort(pageable, Sort.by("extension"))),
                UnusedExtensionReportDto.class);
    }

    public void exportExcelUnusedExtensionReport(String employeeName, String extension,
            LocalDateTime startDate, LocalDateTime endDate,
            Sort sort, int maxRows, OutputStream outputStream, ExcelGeneratorBuilder builder) {
        try {
            builder.generateStreaming(outputStream, (page, size) ->
                    generateUnusedExtensionReport(employeeName, extension, startDate, endDate,
                            PageRequest.of(page, size, sort)).getContent(),
                    ExcelGeneratorBuilder.DEFAULT_STREAMING_PAGE_SIZE, maxRows);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
