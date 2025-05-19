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

    /**
     * PHP equivalent: acumtotal_Insertar (the INSERT part)
     */
    @Transactional
    public CallRecord saveOrUpdateCallRecord(CdrData cdrData, CommunicationLocation commLocation) {
        if (cdrData.isMarkedForQuarantine()) {
            log.warn("CDR marked for quarantine, not saving to CallRecord: {}", cdrData.getRawCdrLine());
            // The calling service should handle saving to FailedCallRecord
            return null;
        }

        // PHP: $_CAMPOS_UNICOS = 'ACUMTOTAL_FECHA_SERVICIO,ACUMTOTAL_FUN_EXTENSION,ACUMTOTAL_TELEFONO_DESTINO,ACUMTOTAL_TIEMPO,ACUMTOTAL_TRONCAL,ACUMTOTAL_COMUBICACION_ID';
        String cdrKeyFields = String.join("|",
                String.valueOf(cdrData.getDateTimeOriginationEpochSeconds()),
                Objects.toString(cdrData.getCallingPartyNumber(), ""), // ACUMTOTAL_FUN_EXTENSION
                Objects.toString(cdrData.getFinalCalledPartyNumber(), ""), // ACUMTOTAL_TELEFONO_DESTINO
                String.valueOf(cdrData.getDurationSeconds()), // ACUMTOTAL_TIEMPO
                Objects.toString(cdrData.getDestDeviceName(), ""), // ACUMTOTAL_TRONCAL (destDeviceName)
                String.valueOf(commLocation.getId()) // ACUMTOTAL_COMUBICACION_ID
        );
        String cdrHash = HashUtil.sha256(cdrKeyFields);


        // PHP: $did = buscarDuplicado($link, $acumcampos);
        CallRecord existingRecord = findByCdrHash(cdrHash);
        if (existingRecord != null) {
            log.warn("Duplicate CDR detected based on hash {}, raw line: {}. Original ID: {}. PHP type: REGDUPLICADO",
                    cdrHash, cdrData.getRawCdrLine(), existingRecord.getId());
            // PHP logic for REGDUPLICADO. For now, we just log and don't insert.
            // If updates were allowed, this is where you'd merge/update.
            // PHP's ReportarErrores would be called with REGDUPLICADO.
            // We can simulate this by marking for quarantine here if strict adherence is needed.
            // cdrData.setMarkedForQuarantine(true);
            // cdrData.setQuarantineReason("Duplicate record (BDD: " + existingRecord.getId() + ")");
            // cdrData.setQuarantineStep("DuplicateCheck_REGDUPLICADO");
            return existingRecord;
        }

        CallRecord callRecord = new CallRecord();
        mapCdrDataToCallRecord(cdrData, callRecord, commLocation, cdrHash);
        
        entityManager.persist(callRecord);
        log.debug("Saved new CallRecord with ID: {}", callRecord.getId());
        return callRecord;
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
        // PHP: $acumcampos["ACUMTOTAL_DIAL"] = $tel_dial; (effective destination)
        callRecord.setDial(cdrData.getEffectiveDestinationNumber() != null ? cdrData.getEffectiveDestinationNumber().substring(0, Math.min(cdrData.getEffectiveDestinationNumber().length(), 50)) : "");
        callRecord.setCommLocationId(commLocation.getId());
        callRecord.setServiceDate(cdrData.getDateTimeOrigination());
        callRecord.setOperatorId(cdrData.getOperatorId());
        // PHP: $acumcampos["ACUMTOTAL_FUN_EXTENSION"] = $extension; (calling party after potential swaps)
        callRecord.setEmployeeExtension(cdrData.getCallingPartyNumber() != null ? cdrData.getCallingPartyNumber().substring(0, Math.min(cdrData.getCallingPartyNumber().length(), 50)) : "");
        callRecord.setEmployeeAuthCode(cdrData.getAuthCodeDescription() != null ? cdrData.getAuthCodeDescription().substring(0, Math.min(cdrData.getAuthCodeDescription().length(), 50)) : "");
        callRecord.setIndicatorId(cdrData.getIndicatorId());
        // PHP: $acumcampos["ACUMTOTAL_TELEFONO_DESTINO"] = $tel_destino; (final called party after potential swaps)
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