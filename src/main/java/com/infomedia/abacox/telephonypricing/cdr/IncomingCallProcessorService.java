// File: com/infomedia/abacox/telephonypricing/cdr/IncomingCallProcessorService.java
package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.CommunicationLocation;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@Log4j2
@RequiredArgsConstructor
public class IncomingCallProcessorService {

    private final OutgoingCallProcessorService outgoingCallProcessorService;
    private final PbxSpecialRuleLookupService pbxSpecialRuleLookupService;
    private final PhoneNumberTransformationService phoneNumberTransformationService;
    private final CallOriginDeterminationService callOriginDeterminationService;
    private final TariffCalculationService tariffCalculationService;
    private final TelephonyTypeLookupService telephonyTypeLookupService;

    /**
     * PHP equivalent: procesaEntrante
     */
    public void processIncoming(CdrData cdrData, CommunicationLocation commLocation, ExtensionLimits limits) {
        log.debug("Processing INCOMING logic for CDR: {}", cdrData.getCtlHash());

        if (cdrData.isInternalCall()) {
            log.debug("Incoming call marked as internal. Inverting and processing as outgoing.");
            CdrUtil.swapPartyInfo(cdrData);
            CdrUtil.swapTrunks(cdrData);
            cdrData.setCallDirection(CallDirection.OUTGOING);
            outgoingCallProcessorService.processOutgoing(cdrData, commLocation, limits, false);
            return;
        }

        String externalCallerId = cdrData.getFinalCalledPartyNumber();
        String ourExtension = cdrData.getCallingPartyNumber();
        log.debug("Incoming call. External Caller ID (from parser swap): {}, Our Extension: {}", externalCallerId, ourExtension);

        Optional<String> pbxTransformedCaller = pbxSpecialRuleLookupService.applyPbxSpecialRule(
                externalCallerId, commLocation.getDirectory(), PbxRuleDirection.INCOMING.getValue()
        );
        if (pbxTransformedCaller.isPresent()) {
            log.debug("External Caller ID '{}' transformed by PBX incoming rule to '{}'", externalCallerId, pbxTransformedCaller.get());
            cdrData.setOriginalDialNumberBeforePbxIncoming(externalCallerId);
            externalCallerId = pbxTransformedCaller.get();
            cdrData.setPbxSpecialRuleAppliedInfo("PBX Incoming Rule: " + cdrData.getFinalCalledPartyNumber() + " -> " + externalCallerId);
        }

        TransformationResult transformedIncomingCME = phoneNumberTransformationService.transformIncomingNumberCME(
                externalCallerId, commLocation.getIndicator().getOriginCountryId()
        );
        Long hintedTelephonyTypeId = null; // Initialize hint
        if (transformedIncomingCME.isTransformed()) {
            log.debug("External Caller ID '{}' transformed by CME rule to '{}'", externalCallerId, transformedIncomingCME.getTransformedNumber());
            cdrData.setOriginalCallerIdBeforeCMETransform(externalCallerId);
            externalCallerId = transformedIncomingCME.getTransformedNumber();
            if (transformedIncomingCME.getNewTelephonyTypeId() != null) {
                hintedTelephonyTypeId = transformedIncomingCME.getNewTelephonyTypeId(); // Capture the hint
                cdrData.setHintedTelephonyTypeIdFromTransform(hintedTelephonyTypeId);
            }
        }

        IncomingCallOriginInfo originInfo = callOriginDeterminationService.determineIncomingCallOrigin(
            externalCallerId,
            hintedTelephonyTypeId, // Pass the hint
            commLocation
        );
        log.debug("Determined incoming call origin info: {}", originInfo);

        cdrData.setTelephonyTypeId(originInfo.getTelephonyTypeId());
        cdrData.setTelephonyTypeName(originInfo.getTelephonyTypeName());
        cdrData.setOperatorId(originInfo.getOperatorId());
        cdrData.setOperatorName(originInfo.getOperatorName());
        cdrData.setIndicatorId(originInfo.getIndicatorId());
        cdrData.setDestinationCityName(originInfo.getDestinationDescription());
        cdrData.setEffectiveDestinationNumber(originInfo.getEffectiveNumber());
        cdrData.setFinalCalledPartyNumber(originInfo.getEffectiveNumber());

        if (cdrData.getTelephonyTypeId() == null || cdrData.getTelephonyTypeId() == TelephonyTypeEnum.UNKNOWN.getValue()) {
             log.warn("Incoming call origin determination resulted in UNKNOWN telephony type. Defaulting to LOCAL.");
             cdrData.setTelephonyTypeId(TelephonyTypeEnum.LOCAL.getValue());
             cdrData.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(TelephonyTypeEnum.LOCAL.getValue()) + " (Default Incoming)");
        }

        tariffCalculationService.calculateTariffsForIncoming(cdrData, commLocation);
        log.debug("Finished processing INCOMING logic. CDR Data: {}", cdrData);
    }
}