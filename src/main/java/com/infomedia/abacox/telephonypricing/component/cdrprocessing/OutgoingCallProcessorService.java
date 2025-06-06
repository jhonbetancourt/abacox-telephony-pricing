// File: com/infomedia/abacox/telephonypricing/cdr/OutgoingCallProcessorService.java
package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import com.infomedia.abacox.telephonypricing.entity.CommunicationLocation;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@Log4j2
@RequiredArgsConstructor
public class OutgoingCallProcessorService {

    private final SpecialServiceLookupService specialServiceLookupService;
    private final InternalCallProcessorService internalCallProcessorService;
    private final PbxSpecialRuleLookupService pbxSpecialRuleLookupService;
    private final TariffCalculationService tariffCalculationService;
    private final PhoneNumberTransformationService phoneNumberTransformationService;

    /**
     * PHP equivalent: procesaSaliente
     */
    public void processOutgoing(CdrData cdrData, ProcessingContext processingContext, ExtensionLimits limits, boolean pbxSpecialRuleAppliedRecursively) {
        CommunicationLocation commLocation = processingContext.getCommLocation();
        log.debug("Processing OUTGOING logic for CDR: {}. Recursive PBX applied: {}", cdrData.getCtlHash(), pbxSpecialRuleAppliedRecursively);

        // PHP: $val_numero = _es_Saliente($info_cdr['dial_number']);
        TransformationResult transformedOutgoingCME = phoneNumberTransformationService.transformOutgoingNumberCME(
                cdrData.getFinalCalledPartyNumber(), commLocation.getIndicator().getOriginCountryId()
        );
        if (transformedOutgoingCME.isTransformed()) {
            log.debug("Outgoing number '{}' transformed by CME rule to '{}'", cdrData.getFinalCalledPartyNumber(), transformedOutgoingCME.getTransformedNumber());
            cdrData.setOriginalDialNumberBeforeCMETransform(cdrData.getFinalCalledPartyNumber());
            cdrData.setFinalCalledPartyNumber(transformedOutgoingCME.getTransformedNumber());
        }
        // For outgoing, effective destination is the final called party number after initial transformations
        cdrData.setEffectiveDestinationNumber(cdrData.getFinalCalledPartyNumber());

        // PHP: if (!$pbx_especial) { if (!$esinterna) { $infovalor = procesaServespecial(...); } }
        if (!pbxSpecialRuleAppliedRecursively && !cdrData.isInternalCall()) {
            List<String> pbxPrefixes = commLocation.getPbxPrefix() != null ? Arrays.asList(commLocation.getPbxPrefix().split(",")) : Collections.emptyList();
            String numToCheckSpecial = CdrUtil.cleanPhoneNumber(
                    cdrData.getEffectiveDestinationNumber(),
                    pbxPrefixes,
                    true // modo_seguro = true for special number check
            ).getCleanedNumber();
            if (numToCheckSpecial != null && !numToCheckSpecial.isEmpty()) {
                log.debug("Checking for special service with number: {}", numToCheckSpecial);
                Optional<SpecialServiceInfo> specialServiceInfoOpt =
                        specialServiceLookupService.findSpecialService(
                                numToCheckSpecial,
                                commLocation.getIndicatorId(), // For outgoing, indicatorId is not destination yet
                                commLocation.getIndicator().getOriginCountryId()
                        );
                if (specialServiceInfoOpt.isPresent()) {
                    SpecialServiceInfo ssi = specialServiceInfoOpt.get();
                    log.info("Call to special service '{}' identified.", ssi.description);
                    cdrData.setTelephonyTypeId(TelephonyTypeEnum.SPECIAL_SERVICES.getValue());
                    cdrData.setTelephonyTypeName(ssi.description);
                    cdrData.setOperatorId(ssi.operatorId);
                    cdrData.setOperatorName(ssi.operatorName);
                    cdrData.setIndicatorId(commLocation.getIndicatorId()); // Origin indicator for outgoing special
                    cdrData.setEffectiveDestinationNumber(numToCheckSpecial); // Use the matched special number
                    cdrData.setSpecialServiceTariff(ssi);
                    tariffCalculationService.calculateTariffsForSpecialService(cdrData);
                    return;
                }
            }
        }

        // PHP: if ($esinterna) { $infovalor = procesaInterna(...); }
        if (cdrData.isInternalCall()) {
            log.debug("Processing as internal call.");
            internalCallProcessorService.processInternal(cdrData, processingContext, limits, pbxSpecialRuleAppliedRecursively);
            return;
        }

        // PHP: if (!$pbx_especial) { $telefono_eval = evaluarPBXEspecial(...); if ($telefono_eval !== '') { procesaSaliente(..., true); return; } }
        if (!pbxSpecialRuleAppliedRecursively) {
            Optional<String> pbxTransformedDest = pbxSpecialRuleLookupService.applyPbxSpecialRule(
                    cdrData.getEffectiveDestinationNumber(), commLocation.getDirectory(), PbxRuleDirection.OUTGOING.getValue()
            );
            if (pbxTransformedDest.isPresent() && !Objects.equals(pbxTransformedDest.get(), cdrData.getEffectiveDestinationNumber())) {
                String originalDest = cdrData.getEffectiveDestinationNumber();
                log.info("Outgoing number '{}' transformed by PBX rule to '{}'. Reprocessing.", originalDest, pbxTransformedDest.get());
                cdrData.setOriginalDialNumberBeforePbxOutgoing(originalDest);
                cdrData.setFinalCalledPartyNumber(pbxTransformedDest.get());
                cdrData.setEffectiveDestinationNumber(pbxTransformedDest.get()); // Update effective destination
                cdrData.setPbxSpecialRuleAppliedInfo("PBX Outgoing Rule: " + originalDest + " -> " + pbxTransformedDest.get());
                processOutgoing(cdrData, processingContext, limits, true); // Recursive call
                return;
            }
        }

        // PHP: $infovalor = procesaSaliente_Complementar(...);
        // This is the main tariffing for external outgoing calls
        log.debug("Proceeding to standard outgoing tariff calculation for destination: {}", cdrData.getEffectiveDestinationNumber());
        tariffCalculationService.calculateTariffsForOutgoing(cdrData, commLocation);
        log.debug("Finished processing OUTGOING logic. CDR Data: {}", cdrData);
    }
}