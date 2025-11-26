package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import com.infomedia.abacox.telephonypricing.component.utils.CompressionZipUtil;
import com.infomedia.abacox.telephonypricing.db.entity.CallRecord;
import com.infomedia.abacox.telephonypricing.db.entity.CommunicationLocation;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@Log4j2
@RequiredArgsConstructor
public class CallRecordPersistenceService {

    @PersistenceContext
    private EntityManager entityManager;
    private final CdrConfigService cdrConfigService;

    /**
     * Batch lookup for duplicate checking.
     * Efficiently checks if a list of hashes exists in the DB using a single query.
     */
    @Transactional(readOnly = true)
    public Set<Long> findExistingHashes(List<Long> hashes) {
        if (hashes == null || hashes.isEmpty()) return Collections.emptySet();

        List<Long> found = entityManager.createQuery(
                        "SELECT cr.ctlHash FROM CallRecord cr WHERE cr.ctlHash IN :hashes", Long.class)
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
     */
    @Transactional
    public CallRecord saveOrUpdateCallRecord(CdrData cdrData, CommunicationLocation commLocation) {
        if (cdrData.isMarkedForQuarantine()) {
            return null;
        }

        Long cdrHash = cdrData.getCtlHash();
        
        // Quick duplicate check (single query)
        List<Long> existing = entityManager.createQuery("SELECT cr.id FROM CallRecord cr WHERE cr.ctlHash = :hash", Long.class)
                .setParameter("hash", cdrHash)
                .getResultList();

        if (!existing.isEmpty()) {
            log.debug("Duplicate CDR detected during sync save. Hash: {}", cdrHash);
            return entityManager.find(CallRecord.class, existing.get(0));
        }

        CallRecord callRecord = createEntityFromDto(cdrData, commLocation);

        try {
            entityManager.persist(callRecord);
            return callRecord;
        } catch (Exception e) {
            log.error("Database error while saving CallRecord during sync process: {}", e.getMessage());
            return null;
        }
    }

    @Transactional(readOnly = true)
    public CallRecord findByCtlHash(Long ctlHash) {
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
     * Handles compression logic:
     * 1. If preCompressedData exists (Batch flow), uses it directly.
     * 2. If not (Sync flow), compresses the raw string inline.
     */
    public void mapCdrDataToCallRecord(CdrData cdrData, CallRecord callRecord, CommunicationLocation commLocation) {

        callRecord.setDial(truncate(cdrData.getEffectiveDestinationNumber(), 50));
        callRecord.setDestinationPhone(truncate(cdrData.getOriginalFinalCalledPartyNumber(), 50));
        callRecord.setCommLocationId(commLocation.getId());

        LocalDateTime serviceDateUtc = cdrData.getDateTimeOrigination();
        if (serviceDateUtc != null) {
            LocalDateTime serviceDateInTargetZone = DateTimeUtil.convertToZone(serviceDateUtc, cdrConfigService.getTargetDatabaseZoneId());
            callRecord.setServiceDate(serviceDateInTargetZone);
        } else {
            callRecord.setServiceDate(null);
        }

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
            callRecord.setFileInfoId(cdrData.getFileInfo().getId().longValue());
        }

        // BINARY DATA HANDLING
        // Priority 1: Use batch-compressed data (CPU heavy work done in parallel worker)
        if (cdrData.getPreCompressedData() != null) {
            callRecord.setCdrString(cdrData.getPreCompressedData());
        } else {
            // Priority 2: Fallback for synchronous reprocessing flows
            try {
                byte[] compressedCdr = CompressionZipUtil.compressString(cdrData.getRawCdrLine());
                callRecord.setCdrString(compressedCdr);
            } catch (IOException e) {
                log.warn("Failed to compress CDR string inline for hash {}: {}", cdrData.getCtlHash(), e.getMessage());
            }
        }

        callRecord.setCtlHash(cdrData.getCtlHash());
    }

    private String truncate(String input, int limit) {
        if (input == null) return "";
        return input.length() > limit ? input.substring(0, limit) : input;
    }
}