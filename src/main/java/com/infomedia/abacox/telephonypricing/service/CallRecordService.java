package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.component.export.excel.ExcelGeneratorBuilder;
import com.infomedia.abacox.telephonypricing.component.export.excel.ExcelGeneratorBuilder;
import com.infomedia.abacox.telephonypricing.db.entity.CallRecord;
import com.infomedia.abacox.telephonypricing.db.projection.EmployeeActivityReport;
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

    public CallRecordService(CallRecordRepository repository) {
        super(repository);
    }

    public ByteArrayResource exportExcel(Specification<CallRecord> specification, Pageable pageable, ExcelGeneratorBuilder builder) {
        Page<CallRecord> collection = find(specification, pageable);
        try {
            InputStream inputStream = builder.withEntities(collection.toList()).generateAsInputStream();
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}