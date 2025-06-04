// File: com/infomedia/abacox/telephonypricing/cdr/CallRecordPersistenceService.java
package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.CallRecord;
import com.infomedia.abacox.telephonypricing.entity.CommunicationLocation;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.NoResultException;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime; // Added

@Service
@Log4j2
public class CallRecordPersistenceService {

    @PersistenceContext
    private EntityManager entityManager;
    private final FailedCallRecordPersistenceService failedCallRecordPersistenceService;
    private final CdrConfigService cdrConfigService; // Added

    // Updated constructor
    public CallRecordPersistenceService(FailedCallRecordPersistenceService failedCallRecordPersistenceService, CdrConfigService cdrConfigService) {
        this.failedCallRecordPersistenceService = failedCallRecordPersistenceService;
        this.cdrConfigService = cdrConfigService;
    }

    @Transactional
    public CallRecord saveOrUpdateCallRecord(CdrData cdrData, CommunicationLocation commLocation) {
        if (cdrData.isMarkedForQuarantine()) {
            log.warn("CDR marked for quarantine, not saving to CallRecord: {}", cdrData.getCtlHash());
            return null;
        }

        String cdrHash = CdrUtil.generateCtlHash(cdrData.getRawCdrLine(), commLocation.getId());
        log.debug("Generated CDR Hash: {}", cdrHash);


        CallRecord existingRecord = findByCtlHash(cdrHash);
        if (existingRecord != null) {
            log.warn("Duplicate CDR detected based on hash {}. Original ID: {}. Quarantining.",
                    cdrHash, existingRecord.getId());
            failedCallRecordPersistenceService.quarantineRecord(cdrData,
                    QuarantineErrorType.DUPLICATE_RECORD,
                    "Duplicate record based on hash. Original ID: " + existingRecord.getId(),
                    "saveOrUpdateCallRecord_DuplicateCheck",
                    existingRecord.getId());
            return existingRecord;
        }

        CallRecord callRecord = new CallRecord();
        mapCdrDataToCallRecord(cdrData, callRecord, commLocation, cdrHash);

        try {
            log.debug("Persisting CallRecord: {}", callRecord);
            entityManager.persist(callRecord);
            log.info("Saved new CallRecord with ID: {} for CDR line: {}", callRecord.getId(), cdrData.getCtlHash());
            return callRecord;
        } catch (Exception e) {
            log.error("Database error while saving CallRecord for CDR: {}. Error: {}", cdrData.getCtlHash(), e.getMessage(), e);
            failedCallRecordPersistenceService.quarantineRecord(cdrData,
                    QuarantineErrorType.DB_INSERT_FAILED,
                    "Database error: " + e.getMessage(),
                    "saveOrUpdateCallRecord_Persist",
                    null);
            return null;
        }
    }

    @Transactional(readOnly = true)
    public CallRecord findByCtlHash(String ctlHash) {
        try {
            return entityManager.createQuery("SELECT cr FROM CallRecord cr WHERE cr.ctlHash = :hash", CallRecord.class)
                    .setParameter("hash", ctlHash)
                    .getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }


    private void mapCdrDataToCallRecord(CdrData cdrData, CallRecord callRecord, CommunicationLocation commLocation, String cdrHash) {
        callRecord.setDial(cdrData.getEffectiveDestinationNumber() != null ? cdrData.getEffectiveDestinationNumber().substring(0, Math.min(cdrData.getEffectiveDestinationNumber().length(), 50)) : "");
        callRecord.setCommLocationId(commLocation.getId());

        // Convert serviceDate to target timezone
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
        callRecord.setDestinationPhone(cdrData.getFinalCalledPartyNumber() != null ? cdrData.getFinalCalledPartyNumber().substring(0, Math.min(cdrData.getFinalCalledPartyNumber().length(), 50)) : "");
        callRecord.setDuration(cdrData.getDurationSeconds());
        callRecord.setRingCount(cdrData.getRingingTimeSeconds());
        callRecord.setTelephonyTypeId(cdrData.getTelephonyTypeId());
        callRecord.setBilledAmount(cdrData.getBilledAmount());
        callRecord.setPricePerMinute(cdrData.getPricePerMinute());
        callRecord.setInitialPrice(cdrData.getInitialPricePerMinute());
        callRecord.setIncoming(cdrData.getCallDirection() == CallDirection.INCOMING);
        callRecord.setTrunk(cdrData.getDestDeviceName() != null ? cdrData.getDestDeviceName().substring(0, Math.min(cdrData.getDestDeviceName().length(), 50)) : "");
        callRecord.setInitialTrunk(cdrData.getOrigDeviceName() != null ? cdrData.getOrigDeviceName().substring(0, Math.min(cdrData.getOrigDeviceName().length(), 50)) : "");
        callRecord.setEmployeeId(cdrData.getEmployeeId());
        callRecord.setEmployeeTransfer(cdrData.getEmployeeTransferExtension() != null ? cdrData.getEmployeeTransferExtension().substring(0, Math.min(cdrData.getEmployeeTransferExtension().length(), 50)) : "");
        callRecord.setTransferCause(cdrData.getTransferCause().getValue());
        callRecord.setAssignmentCause(cdrData.getAssignmentCause().getValue());
        callRecord.setDestinationEmployeeId(cdrData.getDestinationEmployeeId());
        if (cdrData.getFileInfo() != null) {
            callRecord.setFileInfoId(cdrData.getFileInfo().getId());
        }
        callRecord.setCdrString(cdrData.getRawCdrLine());
        callRecord.setCtlHash(cdrHash);
    }
}