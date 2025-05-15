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

     /**
      * Saves a successfully processed CallRecord.
      * Runs in a new transaction to ensure it commits independently.
      * @param callRecord The CallRecord to save.
      * @return The saved CallRecord with its generated ID.
      * @throws RuntimeException if saving fails.
      */
     @Transactional(propagation = Propagation.REQUIRES_NEW)
     public CallRecord saveCallRecord(CallRecord callRecord) {
         try {
             CallRecord savedRecord = callRecordRepository.save(callRecord);
             log.info("Successfully saved CallRecord with ID: {}", savedRecord.getId());
             return savedRecord;
         } catch (Exception e) {
             // Log the error and the record that failed
             log.info("Error saving CallRecord: ID={}, ServiceDate={}, Ext={}, Dest={}, Error: {}",
                     callRecord.getId(), // Will be null if new
                     callRecord.getServiceDate(),
                     callRecord.getEmployeeExtension(),
                     callRecord.getDestinationPhone(),
                     e.getMessage(), e);
             // Re-throw to allow the main processing loop to handle it (e.g., quarantine)
             throw new RuntimeException("Failed to save CallRecord", e);
         }
     }

     /**
      * Saves a FailedCallRecord (quarantined record).
      * Runs in a new transaction. Errors here are logged critically but
      * ideally should not stop the main CDR processing flow.
      * @param failedRecord The FailedCallRecord to save.
      * @return The saved FailedCallRecord or null if saving failed.
      */
     @Transactional(propagation = Propagation.REQUIRES_NEW)
     public FailedCallRecord saveFailedCallRecord(FailedCallRecord failedRecord) {
         try {
             // Add a check for excessively long CDR strings if necessary, though TEXT should handle it
             if (failedRecord.getCdrString() != null && failedRecord.getCdrString().length() > 8000) { // Example limit
                 log.info("Truncating long CDR string before saving to FailedCallRecord. Original length: {}", failedRecord.getCdrString().length());
                 failedRecord.setCdrString(failedRecord.getCdrString().substring(0, 8000) + "...");
             }
             FailedCallRecord savedRecord = failedCallRecordRepository.save(failedRecord);
             log.info("Saved FailedCallRecord with ID: {}, Error Type: {}, Step: {}", savedRecord.getId(), savedRecord.getErrorType(), savedRecord.getProcessingStep());
             return savedRecord;
         } catch (Exception e) {
             // Log error during quarantine saving, but don't stop main processing
             log.info("CRITICAL: Error saving FailedCallRecord for CommLocation {}, Ext {}: {}",
                     failedRecord.getCommLocationId(), failedRecord.getEmployeeExtension(), e.getMessage(), e);
             // Return null to indicate failure, allows main process to continue if possible
             return null;
         }
     }
 }