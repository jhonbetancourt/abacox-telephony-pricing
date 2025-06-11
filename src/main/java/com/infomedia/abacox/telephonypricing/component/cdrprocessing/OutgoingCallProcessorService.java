// File: com/infomedia/abacox/telephonypricing/component/cdrprocessing/OutgoingCallProcessorService.java
package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import com.infomedia.abacox.telephonypricing.db.entity.CommunicationLocation;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
@Log4j2
@RequiredArgsConstructor
public class OutgoingCallProcessorService {

    private final SpecialServiceLookupService specialServiceLookupService;
    private final PbxSpecialRuleLookupService pbxSpecialRuleLookupService;
    private final TariffCalculationService tariffCalculationService;
    private final PhoneNumberTransformationService phoneNumberTransformationService;

    /**
     * PHP equivalent: procesaSaliente
     */
    public void processOutgoing(CdrData cdrData, LineProcessingContext processingContext, boolean pbxSpecialRuleAppliedRecursively) {
        CommunicationLocation commLocation = processingContext.getCommLocation();
        log.debug("Processing OUTGOING logic for CDR: {}. Recursive PBX applied: {}", cdrData.getCtlHash(), pbxSpecialRuleAppliedRecursively);

        TransformationResult transformedOutgoingCME = phoneNumberTransformationService.transformOutgoingNumberCME(
                cdrData.getFinalCalledPartyNumber(), commLocation.getIndicator().getOriginCountryId()
        );
        if (transformedOutgoingCME.isTransformed()) {
            log.debug("Outgoing number '{}' transformed by CME rule to '{}'", cdrData.getFinalCalledPartyNumber(), transformedOutgoingCME.getTransformedNumber());
            cdrData.setOriginalDialNumberBeforeCMETransform(cdrData.getFinalCalledPartyNumber());
            cdrData.setFinalCalledPartyNumber(transformedOutgoingCME.getTransformedNumber());
        }
        cdrData.setEffectiveDestinationNumber(cdrData.getFinalCalledPartyNumber());

        if (!pbxSpecialRuleAppliedRecursively) { // This check is now sufficient, no need for !isInternalCall
            List<String> pbxPrefixes = commLocation.getPbxPrefix() != null ? Arrays.asList(commLocation.getPbxPrefix().split(",")) : Collections.emptyList();
            String numToCheckSpecial = CdrUtil.cleanPhoneNumber(
                    cdrData.getEffectiveDestinationNumber(),
                    pbxPrefixes,
                    true
            ).getCleanedNumber();
            if (numToCheckSpecial != null && !numToCheckSpecial.isEmpty()) {
                log.debug("Checking for special service with number: {}", numToCheckSpecial);
                Optional<SpecialServiceInfo> specialServiceInfoOpt =
                        specialServiceLookupService.findSpecialService(
                                numToCheckSpecial,
                                commLocation.getIndicatorId(),
                                commLocation.getIndicator().getOriginCountryId()
                        );
                if (specialServiceInfoOpt.isPresent()) {
                    SpecialServiceInfo ssi = specialServiceInfoOpt.get();
                    log.info("Call to special service '{}' identified.", ssi.description);
                    cdrData.setTelephonyTypeId(TelephonyTypeEnum.SPECIAL_SERVICES.getValue());
                    cdrData.setTelephonyTypeName(ssi.description);
                    cdrData.setOperatorId(ssi.operatorId);
                    cdrData.setOperatorName(ssi.operatorName);
                    cdrData.setIndicatorId(commLocation.getIndicatorId());
                    cdrData.setEffectiveDestinationNumber(numToCheckSpecial);
                    cdrData.setSpecialServiceTariff(ssi);
                    tariffCalculationService.calculateTariffsForSpecialService(cdrData);
                    return;
                }
            }
        }

        if (!pbxSpecialRuleAppliedRecursively) {
            Optional<String> pbxTransformedDest = pbxSpecialRuleLookupService.applyPbxSpecialRule(
                    cdrData.getEffectiveDestinationNumber(), commLocation.getDirectory(), PbxRuleDirection.OUTGOING.getValue()
            );
            if (pbxTransformedDest.isPresent() && !Objects.equals(pbxTransformedDest.get(), cdrData.getEffectiveDestinationNumber())) {
                String originalDest = cdrData.getEffectiveDestinationNumber();
                log.info("Outgoing number '{}' transformed by PBX rule to '{}'. Reprocessing.", originalDest, pbxTransformedDest.get());
                cdrData.setOriginalDialNumberBeforePbxOutgoing(originalDest);
                cdrData.setFinalCalledPartyNumber(pbxTransformedDest.get());
                cdrData.setEffectiveDestinationNumber(pbxTransformedDest.get());
                cdrData.setPbxSpecialRuleAppliedInfo("PBX Outgoing Rule: " + originalDest + " -> " + pbxTransformedDest.get());
                processOutgoing(cdrData, processingContext, true);
                return;
            }
        }

        log.debug("Proceeding to standard outgoing tariff calculation for destination: {}", cdrData.getEffectiveDestinationNumber());
        tariffCalculationService.calculateTariffsForOutgoing(cdrData, commLocation, processingContext.getCommLocationExtensionLimits());
        log.debug("Finished processing OUTGOING logic. CDR Data: {}", cdrData);
    }
}