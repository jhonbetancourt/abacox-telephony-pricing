package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.entity.CallRecord;
import com.infomedia.abacox.telephonypricing.repository.CallRecordRepository;
import com.infomedia.abacox.telephonypricing.service.common.CrudService;
import org.springframework.stereotype.Service;

@Service
public class CallRecordService extends CrudService<CallRecord, Long, CallRecordRepository> {

    public CallRecordService(CallRecordRepository repository) {
        super(repository);
    }
}
