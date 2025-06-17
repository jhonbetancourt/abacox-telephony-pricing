package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.component.export.excel.GenericExcelGenerator;
import com.infomedia.abacox.telephonypricing.db.entity.CallRecord;
import com.infomedia.abacox.telephonypricing.db.projection.EmployeeActivityReportProjection;
import com.infomedia.abacox.telephonypricing.db.view.CorporateReportView;
import com.infomedia.abacox.telephonypricing.repository.CallRecordRepository;
import com.infomedia.abacox.telephonypricing.repository.view.CorporateReportViewRepository;
import com.infomedia.abacox.telephonypricing.service.common.CrudService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

@Service
public class CallRecordService extends CrudService<CallRecord, Long, CallRecordRepository> {

    private final CorporateReportViewRepository corporateReportViewRepository;


    public CallRecordService(CallRecordRepository repository, CorporateReportViewRepository corporateReportViewRepository) {
        super(repository);
        this.corporateReportViewRepository = corporateReportViewRepository;
    }

    @Transactional(readOnly = true)
    public Page<CorporateReportView> generateCorporateReport(Specification<CorporateReportView> specification, Pageable pageable) {
        return corporateReportViewRepository.findAll(specification, pageable);
    }


    @Transactional(readOnly = true)
    public Page<EmployeeActivityReportProjection> generateEmployeeActivityReport(String employeeName, String employeeExtension, LocalDateTime startDate, LocalDateTime endDate, Pageable pageable) {
        return getRepository().findEmployeeActivityReport(startDate, endDate, employeeName, employeeExtension, pageable);
    }

    public ByteArrayResource exportExcel(Specification<CallRecord> specification, Pageable pageable, Map<String, String> alternativeHeaders
            , Set<String> excludeColumns, Set<String> includeColumns, Map<String, Map<String, String>> valueReplacements) {
        Page<CallRecord> collection = find(specification, pageable);
        try {
            GenericExcelGenerator.ExcelGeneratorBuilder<?> builder = GenericExcelGenerator.builder(collection.toList());
            if (alternativeHeaders != null) builder.withAlternativeHeaderNames(alternativeHeaders);
            if (excludeColumns != null) builder.withExcludedColumnNames(excludeColumns);
            if (includeColumns != null) builder.withIncludedColumnNames(includeColumns);
            if (valueReplacements != null) builder.withValueReplacements(valueReplacements);
            InputStream inputStream = builder.generateAsInputStream();
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}