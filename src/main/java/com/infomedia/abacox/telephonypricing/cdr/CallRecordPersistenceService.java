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

import java.util.Objects;

@Service
@Log4j2
public class CallRecordPersistenceService {

    @PersistenceContext
    private EntityManager entityManager;
    private final FailedCallRecordPersistenceService failedCallRecordPersistenceService;

    public CallRecordPersistenceService(FailedCallRecordPersistenceService failedCallRecordPersistenceService) {
        this.failedCallRecordPersistenceService = failedCallRecordPersistenceService;
    }

    /**
     * PHP equivalent: acumtotal_Insertar (the INSERT part)
     */
    @Transactional
    public CallRecord saveOrUpdateCallRecord(CdrData cdrData, CommunicationLocation commLocation) {
        if (cdrData.isMarkedForQuarantine()) {
            log.warn("CDR marked for quarantine, not saving to CallRecord: {}", cdrData.getRawCdrLine());
            // The quarantine should have already happened in CdrFileProcessorService or CdrEnrichmentService
            return null;
        }

        String cdrKeyFields = String.join("|",
                String.valueOf(cdrData.getDateTimeOriginationEpochSeconds()),
                Objects.toString(cdrData.getCallingPartyNumber(), ""),
                Objects.toString(cdrData.getFinalCalledPartyNumber(), ""),
                String.valueOf(cdrData.getDurationSeconds()),
                Objects.toString(cdrData.getDestDeviceName(), ""), // Trunk
                String.valueOf(commLocation.getId())
        );
        String cdrHash = HashUtil.sha256(cdrKeyFields);
        log.debug("Generated CDR Hash for key fields '{}': {}", cdrKeyFields, cdrHash);


        CallRecord existingRecord = findByCdrHash(cdrHash);
        if (existingRecord != null) {
            log.warn("Duplicate CDR detected based on hash {}, raw line: {}. Original ID: {}. Quarantining.",
                    cdrHash, cdrData.getRawCdrLine(), existingRecord.getId());
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
            log.info("Saved new CallRecord with ID: {} for CDR line: {}", callRecord.getId(), cdrData.getRawCdrLine());
            return callRecord;
        } catch (Exception e) {
            log.error("Database error while saving CallRecord for CDR: {}. Error: {}", cdrData.getRawCdrLine(), e.getMessage(), e);
            // PHP: if (!$insertar) { ... $infoerror = bd_captura_error($link); $causaerror = 'NOINSERTA'; ... ReportarErrores(...) }
            failedCallRecordPersistenceService.quarantineRecord(cdrData,
                    QuarantineErrorType.DB_INSERT_FAILED,
                    "Database error: " + e.getMessage(),
                    "saveOrUpdateCallRecord_Persist",
                    null);
            return null;
        }
    }

    @Transactional(readOnly = true)
    public CallRecord findByCdrHash(String cdrHash) {
        try {
            return entityManager.createQuery("SELECT cr FROM CallRecord cr WHERE cr.cdrHash = :hash", CallRecord.class)
                    .setParameter("hash", cdrHash)
                    .getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }


    private void mapCdrDataToCallRecord(CdrData cdrData, CallRecord callRecord, CommunicationLocation commLocation, String cdrHash) {
        callRecord.setDial(cdrData.getEffectiveDestinationNumber() != null ? cdrData.getEffectiveDestinationNumber().substring(0, Math.min(cdrData.getEffectiveDestinationNumber().length(), 50)) : "");
        callRecord.setCommLocationId(commLocation.getId());
        callRecord.setServiceDate(cdrData.getDateTimeOrigination());
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
            callRecord.setFileInfoId(cdrData.getFileInfo().getId().longValue());
        }
        callRecord.setCdrHash(cdrHash);
    }
}