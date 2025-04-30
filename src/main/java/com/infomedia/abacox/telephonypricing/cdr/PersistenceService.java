package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.CallRecord;
import com.infomedia.abacox.telephonypricing.entity.FailedCallRecord;
import com.infomedia.abacox.telephonypricing.repository.CallRecordRepository;
import com.infomedia.abacox.telephonypricing.repository.FailedCallRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Log4j2
public class PersistenceService {

    private final CallRecordRepository callRecordRepository;
    private final FailedCallRecordRepository failedCallRecordRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW) // Ensure save happens in its own transaction
    public CallRecord saveCallRecord(CallRecord callRecord) {
        try {
            CallRecord savedRecord = callRecordRepository.save(callRecord);
            log.debug("Successfully saved CallRecord with ID: {}", savedRecord.getId());
            return savedRecord;
        } catch (Exception e) {
            log.error("Error saving CallRecord: {}", callRecord, e);
            // Optionally re-throw or handle specific exceptions (e.g., DataIntegrityViolationException for duplicates)
            // Consider creating a FailedCallRecord here if saving fails critically
            throw e; // Re-throw to indicate failure to the caller
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW) // Ensure save happens in its own transaction
    public FailedCallRecord saveFailedCallRecord(FailedCallRecord failedRecord) {
        try {
            FailedCallRecord savedRecord = failedCallRecordRepository.save(failedRecord);
            log.warn("Saved FailedCallRecord with ID: {}, Error Type: {}", savedRecord.getId(), savedRecord.getErrorType());
            return savedRecord;
        } catch (Exception e) {
            // Log error during quarantine saving, but don't stop main processing if possible
            log.error("CRITICAL: Error saving FailedCallRecord: {}", failedRecord, e);
            // Depending on requirements, maybe throw a specific exception or just log
            return null; // Indicate failure to save quarantine record
        }
    }
}