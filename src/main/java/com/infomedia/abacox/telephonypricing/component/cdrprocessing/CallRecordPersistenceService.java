// File: com/infomedia/abacox/telephonypricing/component/cdrprocessing/CallRecordPersistenceService.java
package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import com.infomedia.abacox.telephonypricing.component.utils.Compression7zUtil;
import com.infomedia.abacox.telephonypricing.db.entity.CallRecord;
import com.infomedia.abacox.telephonypricing.db.entity.CommunicationLocation;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.NoResultException;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.LocalDateTime;

@Service
@Log4j2
public class CallRecordPersistenceService {

    @PersistenceContext
    private EntityManager entityManager;
    private final FailedCallRecordPersistenceService failedCallRecordPersistenceService;
    private final CdrConfigService cdrConfigService;

    public CallRecordPersistenceService(FailedCallRecordPersistenceService failedCallRecordPersistenceService, CdrConfigService cdrConfigService) {
        this.failedCallRecordPersistenceService = failedCallRecordPersistenceService;
        this.cdrConfigService = cdrConfigService;
    }

    @Transactional
    public CallRecord saveOrUpdateCallRecord(CdrData cdrData, CommunicationLocation commLocation) {
        if (cdrData.isMarkedForQuarantine()) {
            log.debug("CDR marked for quarantine, not saving to CallRecord: {}", cdrData.getCtlHash());
            return null;
        }

        Long cdrHash = cdrData.getCtlHash();
        log.debug("Generated CDR Hash: {}", cdrHash);


        CallRecord existingRecord = findByCtlHash(cdrHash);
        if (existingRecord != null) {
            log.debug("Duplicate CDR detected based on hash {}. Original ID: {}. Ignoring.",
                    cdrHash, existingRecord.getId());
            // As per request, do not quarantine, just ignore.
            return existingRecord;
        }

        CallRecord callRecord = new CallRecord();
        mapCdrDataToCallRecord(cdrData, callRecord, commLocation);

        try {
            log.debug("Persisting CallRecord: {}", callRecord);
            entityManager.persist(callRecord);
            log.debug("Saved new CallRecord with ID: {} for CDR line: {}", callRecord.getId(), cdrData.getCtlHash());
            return callRecord;
        } catch (Exception e) {
            log.debug("Database error while saving CallRecord for CDR: {}. Error: {}", cdrData.getCtlHash(), e.getMessage(), e);
            failedCallRecordPersistenceService.quarantineRecord(cdrData,
                    QuarantineErrorType.DB_INSERT_FAILED,
                    "Database error: " + e.getMessage(),
                    "saveOrUpdateCallRecord_Persist",
                    null);
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


    public void mapCdrDataToCallRecord(CdrData cdrData, CallRecord callRecord, CommunicationLocation commLocation) {

        callRecord.setDial(cdrData.getEffectiveDestinationNumber() != null ? cdrData.getEffectiveDestinationNumber().substring(0, Math.min(cdrData.getEffectiveDestinationNumber().length(), 50)) : "");
        callRecord.setDestinationPhone(cdrData.getOriginalFinalCalledPartyNumber() != null ? cdrData.getOriginalFinalCalledPartyNumber().substring(0, Math.min(cdrData.getOriginalFinalCalledPartyNumber().length(), 50)) : "");
        callRecord.setCommLocationId(commLocation.getId());

        LocalDateTime serviceDateUtc = cdrData.getDateTimeOrigination();
        if (serviceDateUtc != null) {
            LocalDateTime serviceDateInTargetZone = DateTimeUtil.convertToZone(serviceDateUtc, cdrConfigService.getTargetDatabaseZoneId());
            callRecord.setServiceDate(serviceDateInTargetZone);
            log.trace("ServiceDate UTC: {} -> TargetZone ({}): {}", serviceDateUtc, cdrConfigService.getTargetDatabaseZoneId(), serviceDateInTargetZone);
        } else {
            callRecord.setServiceDate(null);
        }

        callRecord.setOperatorId(cdrData.getOperatorId());
        callRecord.setEmployeeExtension(cdrData.getCallingPartyNumber() != null ? cdrData.getCallingPartyNumber().substring(0, Math.min(cdrData.getCallingPartyNumber().length(), 50)) : "");
        callRecord.setEmployeeAuthCode(cdrData.getAuthCodeDescription() != null ? cdrData.getAuthCodeDescription().substring(0, Math.min(cdrData.getAuthCodeDescription().length(), 50)) : "");
        callRecord.setIndicatorId(cdrData.getIndicatorId());
        callRecord.setDuration(cdrData.getDurationSeconds());
        callRecord.setRingCount(cdrData.getRingingTimeSeconds());
        callRecord.setTelephonyTypeId(cdrData.getTelephonyTypeId());
        callRecord.setBilledAmount(cdrData.getBilledAmount());
        callRecord.setPricePerMinute(cdrData.getPricePerMinute());
        callRecord.setInitialPrice(cdrData.getInitialPricePerMinute());
        callRecord.setIsIncoming(cdrData.getCallDirection() == CallDirection.INCOMING);
        callRecord.setTrunk(cdrData.getDestDeviceName() != null ? cdrData.getDestDeviceName().substring(0, Math.min(cdrData.getDestDeviceName().length(), 50)) : "");
        callRecord.setInitialTrunk(cdrData.getOrigDeviceName() != null ? cdrData.getOrigDeviceName().substring(0, Math.min(cdrData.getOrigDeviceName().length(), 50)) : "");
        callRecord.setEmployeeId(cdrData.getEmployeeId());
        callRecord.setEmployeeTransfer(cdrData.getEmployeeTransferExtension() != null ? cdrData.getEmployeeTransferExtension().substring(0, Math.min(cdrData.getEmployeeTransferExtension().length(), 50)) : "");
        callRecord.setTransferCause(cdrData.getTransferCause().getValue());
        callRecord.setAssignmentCause(cdrData.getAssignmentCause().getValue());
        callRecord.setDestinationEmployeeId(cdrData.getDestinationEmployeeId());
        if (cdrData.getFileInfo() != null) {
            callRecord.setFileInfoId(cdrData.getFileInfo().getId().longValue());
        }

        // Compress the CDR string before saving
        try {
            byte[] compressedCdr = Compression7zUtil.compressString(cdrData.getRawCdrLine());
            callRecord.setCdrString(compressedCdr);
            log.trace("Compressed CDR string from {} to {} bytes",
                    cdrData.getRawCdrLine().length(), compressedCdr.length);
        } catch (IOException e) {
            log.warn("Failed to compress CDR string for hash {}: {}.",
                    cdrData.getCtlHash(), e.getMessage());
        }

        callRecord.setCtlHash(cdrData.getCtlHash());
    }
}