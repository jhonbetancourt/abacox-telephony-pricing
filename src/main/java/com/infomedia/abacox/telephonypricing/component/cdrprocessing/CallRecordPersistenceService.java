package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import com.infomedia.abacox.telephonypricing.db.entity.CallRecord;
import com.infomedia.abacox.telephonypricing.db.entity.CommunicationLocation;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.*;

@Service
@Log4j2
@RequiredArgsConstructor
public class CallRecordPersistenceService {

    @PersistenceContext
    private EntityManager entityManager;
    private final CdrConfigService cdrConfigService;
    
    // Inject the Transaction Manager
    private final PlatformTransactionManager transactionManager;

    /**
     * Batch lookup for duplicate checking.
     * Efficiently checks if a list of hashes exists in the DB using a single query.
     */
    @Transactional(readOnly = true)
    public Set<UUID> findExistingHashes(List<UUID> hashes) { // Changed List<Long> to List<UUID>
        if (hashes == null || hashes.isEmpty()) return Collections.emptySet();

        List<UUID> found = entityManager.createQuery(
                        "SELECT cr.ctlHash FROM CallRecord cr WHERE cr.ctlHash IN :hashes", UUID.class) // Changed Long.class to UUID.class
                .setParameter("hashes", hashes)
                .getResultList();

        return new HashSet<>(found);
    }

    /**
     * Creates a CallRecord entity in memory from the DTO.
     * Does NOT persist to DB. Used by the BatchWorker.
     */
    public CallRecord createEntityFromDto(CdrData cdrData, CommunicationLocation commLocation) {
        CallRecord callRecord = new CallRecord();
        mapCdrDataToCallRecord(cdrData, callRecord, commLocation);
        return callRecord;
    }

    /**
     * Synchronous persistence method.
     * Used ONLY for Admin Reprocessing or specific synchronous flows.
     * Performs inline duplicate check and inline compression.
     * 
     * Concurrency Safety:
     * Uses TransactionTemplate with REQUIRES_NEW to isolate the insert attempt.
     */
    public CallRecord saveOrUpdateCallRecord(CdrData cdrData, CommunicationLocation commLocation) {
        if (cdrData.isMarkedForQuarantine()) {
            return null;
        }

        UUID cdrHash = cdrData.getCtlHash(); // Changed Long to UUID

        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        try {
            return txTemplate.execute(status -> {
                // 1. Quick duplicate check
                List<Long> existingIds = entityManager.createQuery("SELECT cr.id FROM CallRecord cr WHERE cr.ctlHash = :hash", Long.class)
                        .setParameter("hash", cdrHash)
                        .getResultList();

                if (!existingIds.isEmpty()) {
                    log.debug("Duplicate CDR detected during sync save. Hash: {}", cdrHash);
                    return entityManager.find(CallRecord.class, existingIds.get(0));
                }

                CallRecord callRecord = createEntityFromDto(cdrData, commLocation);
                entityManager.persist(callRecord);
                entityManager.flush();

                return callRecord;
            });

        } catch (Exception e) {
            if (isDuplicateKeyException(e)) {
                log.debug("Race condition detected for CallRecord hash {}. Fetching existing.", cdrHash);
                return txTemplate.execute(retryStatus -> findByCtlHash(cdrHash));
            } else {
                log.error("Database error while saving CallRecord during sync process: {}", e.getMessage());
                return null;
            }
        }
    }

    @Transactional(readOnly = true)
    public CallRecord findByCtlHash(UUID ctlHash) { // Changed Long to UUID
        try {
            return entityManager.createQuery("SELECT cr FROM CallRecord cr WHERE cr.ctlHash = :hash", CallRecord.class)
                    .setParameter("hash", ctlHash)
                    .getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }

    @Transactional
    public int deleteByFileInfoId(Long fileInfoId) {
        if (fileInfoId == null) return 0;
        int deletedCount = entityManager.createQuery("DELETE FROM CallRecord cr WHERE cr.fileInfoId = :fileInfoId")
                .setParameter("fileInfoId", fileInfoId)
                .executeUpdate();
        log.debug("Deleted {} CallRecord(s) for FileInfo ID: {}", deletedCount, fileInfoId);
        return deletedCount;
    }

    /**
     * Maps fields from CdrData DTO to CallRecord Entity.
     */
    public void mapCdrDataToCallRecord(CdrData cdrData, CallRecord callRecord, CommunicationLocation commLocation) {

        callRecord.setDial(truncate(cdrData.getEffectiveDestinationNumber(), 50));
        callRecord.setDestinationPhone(truncate(cdrData.getOriginalFinalCalledPartyNumber(), 50));
        callRecord.setCommLocationId(commLocation.getId());
        callRecord.setServiceDate(cdrData.getDateTimeOrigination());
        callRecord.setOperatorId(cdrData.getOperatorId());
        callRecord.setEmployeeExtension(truncate(cdrData.getCallingPartyNumber(), 50));
        callRecord.setEmployeeAuthCode(truncate(cdrData.getAuthCodeDescription(), 50));
        callRecord.setIndicatorId(cdrData.getIndicatorId());
        callRecord.setDuration(cdrData.getDurationSeconds());
        callRecord.setRingCount(cdrData.getRingingTimeSeconds());
        callRecord.setTelephonyTypeId(cdrData.getTelephonyTypeId());
        callRecord.setBilledAmount(cdrData.getBilledAmount());
        callRecord.setPricePerMinute(cdrData.getPricePerMinute());
        callRecord.setInitialPrice(cdrData.getInitialPricePerMinute());
        callRecord.setIsIncoming(cdrData.getCallDirection() == CallDirection.INCOMING);
        callRecord.setTrunk(truncate(cdrData.getDestDeviceName(), 50));
        callRecord.setInitialTrunk(truncate(cdrData.getOrigDeviceName(), 50));
        callRecord.setEmployeeId(cdrData.getEmployeeId());
        callRecord.setEmployeeTransfer(truncate(cdrData.getEmployeeTransferExtension(), 50));
        
        if (cdrData.getTransferCause() != null) callRecord.setTransferCause(cdrData.getTransferCause().getValue());
        if (cdrData.getAssignmentCause() != null) callRecord.setAssignmentCause(cdrData.getAssignmentCause().getValue());
        
        callRecord.setDestinationEmployeeId(cdrData.getDestinationEmployeeId());
        
        if (cdrData.getFileInfo() != null) {
            callRecord.setFileInfoId(cdrData.getFileInfo().getId());
        }

        callRecord.setCtlHash(cdrData.getCtlHash());
    }

    private boolean isDuplicateKeyException(Exception e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof ConstraintViolationException || 
                (cause.getMessage() != null && cause.getMessage().toLowerCase().contains("duplicate key"))) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private String truncate(String input, int limit) {
        if (input == null) return "";
        return input.length() > limit ? input.substring(0, limit) : input;
    }
}