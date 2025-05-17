package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.CallRecord;
import com.infomedia.abacox.telephonypricing.entity.CommunicationLocation;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Log4j2
public class EnrichmentService {

    private final EmployeeLookupService employeeLookupService;

    private final EmployeeAssigner employeeAssigner;
    private final PbxRuleApplier pbxRuleApplier;
    private final SpecialServicePricer specialServicePricer;
    private final InternalCallPricer internalCallPricer;
    private final ExternalCallPricer externalCallPricer;
    private final CallRecordUpdater callRecordUpdater;


    public void enrichCallRecord(CallRecord callRecord, RawCiscoCdrData rawData, CommunicationLocation commLocation) {
        InternalExtensionLimitsDto internalLimits = employeeLookupService.getInternalExtensionLimits(
            commLocation.getIndicator() != null ? commLocation.getIndicator().getOriginCountryId() : null,
            commLocation.getId()
        );

        employeeAssigner.assignEmployee(callRecord, rawData, commLocation, internalLimits);

        // Store the original dialed number before PBX rules for 'dial' field later
        String originalDialedNumberForDialField = rawData.getEffectiveDestinationNumber();

        String effectiveDialedNumber = pbxRuleApplier.applyPbxRules(
            rawData.getEffectiveDestinationNumber(), 
            commLocation, 
            callRecord.isIncoming()
        );
        
        // Set 'dial' field after PBX rules, but from original effective destination before PBX if it was complex.
        // The PHP logic for 'dial' is `cdr.dial = limpiar_caracteres(info_llamada.dial)`
        // where `info_llamada.dial` is set from `tel_destino_efectivo` which is after PBX rules.
        callRecord.setDial(CdrHelper.cleanPhoneNumber(effectiveDialedNumber));


        callRecordUpdater.setDefaultErrorTelephonyType(callRecord); 

        if (effectiveDialedNumber == null || effectiveDialedNumber.isEmpty()) {
            log.warn("Effective dialed number is empty for CDR hash: {}", callRecord.getCdrHash());
            // setDefaultErrorTelephonyType already called
        } else {
            Long originCountryId = commLocation.getIndicator() != null ? commLocation.getIndicator().getOriginCountryId() : null;
            if (originCountryId == null) {
                log.error("Origin Country ID is null for commLocationId: {}. Cannot proceed with pricing.", commLocation.getId());
                // setDefaultErrorTelephonyType already called
            } else {
                boolean handled = false;
                // 1. Special Services
                if (!handled) {
                    handled = specialServicePricer.priceAsSpecialService(callRecord, effectiveDialedNumber, commLocation, originCountryId);
                }
                // 2. Internal Calls
                if (!handled) {
                    handled = internalCallPricer.priceAsInternalCall(callRecord, rawData, effectiveDialedNumber, commLocation, internalLimits, originCountryId);
                }
                // 3. External Calls
                if (!handled) {
                    externalCallPricer.priceExternalCall(callRecord, rawData, effectiveDialedNumber, commLocation, originCountryId);
                }
            }
        }

        // Final check for SIN_CONSUMO
        if (callRecord.getDuration() != null && callRecord.getDuration() == 0 &&
            callRecord.getTelephonyTypeId() != null &&
            !Objects.equals(callRecord.getTelephonyTypeId(), TelephonyTypeConstants.ERRORES) &&
            !Objects.equals(callRecord.getTelephonyTypeId(), TelephonyTypeConstants.SIN_CONSUMO)) {
            
            log.debug("Call duration is zero, marking as SIN_CONSUMO. CDR Hash: {}", callRecord.getCdrHash());
            callRecordUpdater.setTelephonyTypeAndOperator(callRecord, TelephonyTypeConstants.SIN_CONSUMO,
                commLocation.getIndicator() != null ? commLocation.getIndicator().getOriginCountryId() : null,
                null); 
            callRecord.setBilledAmount(BigDecimal.ZERO);
            callRecord.setPricePerMinute(BigDecimal.ZERO);
            callRecord.setInitialPrice(BigDecimal.ZERO);
        }
    }
}