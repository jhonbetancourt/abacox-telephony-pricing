package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.component.export.excel.GenericExcelGenerator;
import com.infomedia.abacox.telephonypricing.entity.CallRecord;
import com.infomedia.abacox.telephonypricing.entity.view.CorporateReportView;
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
import java.util.Map;
import java.util.Set;

@Service
public class CallRecordService extends CrudService<CallRecord, Long, CallRecordRepository> {

    private final CorporateReportViewRepository reportViewRepository;


    public CallRecordService(CallRecordRepository repository, CorporateReportViewRepository reportViewRepository) {
        super(repository);
        this.reportViewRepository = reportViewRepository;
    }

    @Transactional(readOnly = true)
    public Page<CorporateReportView> generateCorporateReport(Specification<CorporateReportView> specification, Pageable pageable) {
        // The entire logic is now a single, clean call to the repository.
        // No manual mapping, no enrichment, no complex logic is needed here.
        return reportViewRepository.findAll(specification, pageable);
    }

    public ByteArrayResource exportExcel(Specification<CallRecord> specification, Pageable pageable, Map<String, String> alternativeHeaders, Set<String> excludeColumns) {
        Page<CallRecord> collection = find(specification, pageable);
        try {
            InputStream inputStream = GenericExcelGenerator.generateExcelInputStream(collection.toList()
                    , Set.of(), alternativeHeaders, excludeColumns);
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}