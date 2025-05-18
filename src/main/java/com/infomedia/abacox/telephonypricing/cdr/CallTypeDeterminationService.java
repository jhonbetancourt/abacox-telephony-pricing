package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.CommunicationLocation;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

@Service
@Log4j2
@RequiredArgsConstructor
public class CallTypeDeterminationService {

    private final EmployeeLookupService employeeLookupService;
    private final PbxSpecialRuleLookupService pbxSpecialRuleLookupService;
    private final SpecialServiceLookupService specialServiceLookupService;
    private final TelephonyTypeLookupService telephonyTypeLookupService;
    private ExtensionLimits extensionLimits; // To cache limits for a processing run


    private ExtensionLimits getExtensionLimits(CommunicationLocation commLocation) {
        if (this.extensionLimits == null) { // Lazy load per batch/stream
            this.extensionLimits = employeeLookupService.getExtensionLimits(
                    commLocation.getIndicator().getOriginCountryId(),
                    commLocation.getId(),
                    commLocation.getPlantTypeId()
            );
        }
        return this.extensionLimits;
    }

    // To be called at the start of processing a new stream/batch
    public void resetExtensionLimitsCache() {
        this.extensionLimits = null;
    }


    public void determineCallTypeAndDirection(CdrData cdrData, CommunicationLocation commLocation) {
        // This combines logic from procesaEntrante, procesaSaliente, procesaInterna, es_llamada_interna

        ExtensionLimits limits = getExtensionLimits(commLocation);

        // Initial direction is often set by the parser based on partitions. Refine here.
        boolean isCallingPartyExt = employeeLookupService.isPossibleExtension(cdrData.getCallingPartyNumber(), limits);
        boolean isFinalCalledPartyExt = employeeLookupService.isPossibleExtension(cdrData.getFinalCalledPartyNumber(), limits);

        // PHP's es_llamada_interna
        if (isCallingPartyExt && isFinalCalledPartyExt) {
            cdrData.setInternalCall(true);
            // Direction for internal calls is typically outgoing from the perspective of callingPartyNumber
            cdrData.setCallDirection(CallDirection.OUTGOING);
        } else if (!isCallingPartyExt && isFinalCalledPartyExt) {
            // If caller is not an extension but callee is, it's likely incoming
            cdrData.setCallDirection(CallDirection.INCOMING);
            // PHP's InvertirLlamada for incoming: swap ext and dial_number
            String temp = cdrData.getCallingPartyNumber();
            cdrData.setCallingPartyNumber(cdrData.getFinalCalledPartyNumber());
            cdrData.setFinalCalledPartyNumber(temp);
            // Also swap partitions
            temp = cdrData.getCallingPartyNumberPartition();
            cdrData.setCallingPartyNumberPartition(cdrData.getFinalCalledPartyNumberPartition());
            cdrData.setFinalCalledPartyNumberPartition(temp);
        } else {
            // Default to outgoing if not clearly incoming or internal-to-internal
            cdrData.setCallDirection(CallDirection.OUTGOING);
        }

        // Apply _es_Saliente and _esEntrante_60 logic (Colombian specific)
        // These were called in txt2dbv8.php's procesaSaliente/Entrante
        // This is a simplification; the PHP code has complex interactions with global vars.
        String originalDialed = cdrData.getCallDirection() == CallDirection.OUTGOING ? cdrData.getFinalCalledPartyNumber() : cdrData.getCallingPartyNumber();
        PhoneNumberTransformationResult transformedPhone = transformPhoneNumber(
                originalDialed,
                cdrData.getCallDirection(),
                commLocation
        );

        if (transformedPhone.isTransformed()) {
            cdrData.getAdditionalData().put("originalDialNumberBeforeTransform", originalDialed);
            if (cdrData.getCallDirection() == CallDirection.OUTGOING) {
                cdrData.setFinalCalledPartyNumber(transformedPhone.getTransformedNumber());
            } else { // INCOMING
                cdrData.setCallingPartyNumber(transformedPhone.getTransformedNumber());
            }
            if (transformedPhone.getNewTelephonyTypeId() != null) {
                // This implies a strong typing of the call due to transformation
                cdrData.setTelephonyTypeId(transformedPhone.getNewTelephonyTypeId());
                // Potentially set telephonyTypeName as well
            }
        }
        cdrData.setEffectiveDestinationNumber(cdrData.getFinalCalledPartyNumber()); // Default before PBX rules

        // Handle PBX Special Rules (evaluarPBXEspecial)
        // This can change the effective destination number
        String numberToEvaluateForPbx = cdrData.getCallDirection() == CallDirection.OUTGOING ?
                cdrData.getFinalCalledPartyNumber() :
                (cdrData.isInternalCall() ? cdrData.getFinalCalledPartyNumber() : cdrData.getCallingPartyNumber());

        int pbxRuleDirection = 0; // 0=both, 1=incoming, 2=outgoing, 3=internal (PHP mapping)
        if (cdrData.isInternalCall()) pbxRuleDirection = 3;
        else if (cdrData.getCallDirection() == CallDirection.INCOMING) pbxRuleDirection = 1;
        else pbxRuleDirection = 2;

        Optional<String> pbxTransformedNum = pbxSpecialRuleLookupService.applyPbxSpecialRule(
                numberToEvaluateForPbx,
                commLocation.getDirectory(),
                pbxRuleDirection
        );

        if (pbxTransformedNum.isPresent()) {
            String transformed = pbxTransformedNum.get();
            cdrData.setPbxSpecialRuleAppliedInfo("Rule applied: " + numberToEvaluateForPbx + " -> " + transformed);
            if (cdrData.getCallDirection() == CallDirection.OUTGOING || cdrData.isInternalCall()) {
                cdrData.setEffectiveDestinationNumber(transformed);
                // If it was internal but now looks external due to PBX rule, update internal flag
                if (cdrData.isInternalCall() && !employeeLookupService.isPossibleExtension(transformed, limits)) {
                    cdrData.setInternalCall(false);
                }
            } else { // INCOMING
                cdrData.setCallingPartyNumber(transformed); // If PBX rule modifies incoming caller ID
            }
        }


        // Handle Special Service Numbers (procesaServespecial) - for outgoing calls
        if (cdrData.getCallDirection() == CallDirection.OUTGOING && !cdrData.isInternalCall()) {
            String numToCheckSpecial = CdrParserUtil.cleanPhoneNumber(
                    cdrData.getEffectiveDestinationNumber(),
                    commLocation.getPbxPrefix() != null ? Arrays.asList(commLocation.getPbxPrefix().split(",")) : Collections.emptyList(),
                    true // modo_seguro = true
            );
            if (!numToCheckSpecial.isEmpty()) {
                Optional<SpecialServiceInfo> specialServiceInfo =
                        specialServiceLookupService.findSpecialService(
                                numToCheckSpecial,
                                commLocation.getIndicatorId(),
                                commLocation.getIndicator().getOriginCountryId()
                        );
                if (specialServiceInfo.isPresent()) {
                    cdrData.setTelephonyTypeId(TelephonyTypeEnum.SPECIAL_SERVICES.getValue());
                    cdrData.setTelephonyTypeName(specialServiceInfo.get().description); // Or a fixed name
                    cdrData.setOperatorId(specialServiceInfo.get().operatorId); // From operador_interno
                    // Tariff info is part of SpecialServiceInfo, will be used by TariffCalculationService
                    cdrData.getAdditionalData().put("specialServiceTariff", specialServiceInfo.get());
                    log.debug("Call to special service: {}", numToCheckSpecial);
                    return; // Processing for special service is distinct
                }
            }
        }

        // If internal, set telephony type (tipo_llamada_interna)
        if (cdrData.isInternalCall()) {
            // PHP's tipo_llamada_interna is complex, involving employee lookups for both ends
            // and comparing their locations (subdivision, city, country).
            // This simplified version just marks it as a generic internal type.
            // A more complete version would call EmployeeLookupService for both ends.
            cdrData.setTelephonyTypeId(TelephonyTypeEnum.INTERNAL_SIMPLE.getValue()); // Default internal
            cdrData.setTelephonyTypeName("Internal"); // Or lookup
            // Operator for internal calls
            cdrData.setOperatorId(telephonyTypeLookupService.getInternalOperatorId(TelephonyTypeEnum.INTERNAL_SIMPLE.getValue(), commLocation.getIndicator().getOriginCountryId()));
        }

        // For non-internal, non-special outgoing or incoming, telephony type will be determined by TariffCalculationService
        // based on prefixes and destination.
    }

    // Mimics _esEntrante_60 and _es_Saliente
    private PhoneNumberTransformationResult transformPhoneNumber(String phoneNumber, CallDirection direction, CommunicationLocation commLocation) {
        String originalPhoneNumber = phoneNumber;
        Long newTelephonyTypeId = null;

        if (commLocation.getIndicator().getOriginCountryId() == 1L) { // Colombia MPORIGEN_ID = 1
            int len = phoneNumber.length();
            if (direction == CallDirection.INCOMING) { // _esEntrante_60 logic
                String p2 = len >= 2 ? phoneNumber.substring(0, 2) : "";
                String p3 = len >= 3 ? phoneNumber.substring(0, 3) : "";
                String p4 = len >= 4 ? phoneNumber.substring(0, 4) : "";

                if (len == 12) {
                    if ("573".equals(p3) || "603".equals(p3)) {
                        phoneNumber = phoneNumber.substring(len - 10);
                        newTelephonyTypeId = TelephonyTypeEnum.CELLULAR.getValue();
                    } else if ("6060".equals(p4) || "5760".equals(p4)) {
                        phoneNumber = phoneNumber.substring(len - 8);
                    }
                } else if (len == 11) {
                    if ("604".equals(p3)) {
                        phoneNumber = phoneNumber.substring(len - 8);
                    } else if ("03".equals(p2)) {
                        String n3 = phoneNumber.substring(1, 4); // Get 3 digits after '0'
                        try {
                            int n3val = Integer.parseInt(n3);
                            if (n3val >= 300 && n3val <= 350) { // Colombian mobile prefixes
                                phoneNumber = phoneNumber.substring(len - 10);
                                newTelephonyTypeId = TelephonyTypeEnum.CELLULAR.getValue();
                            }
                        } catch (NumberFormatException ignored) {
                        }
                    }
                } else if (len == 10) {
                    if ("60".equals(p2) || "57".equals(p2)) { // National prefix
                        phoneNumber = phoneNumber.substring(len - 8);
                    } else if (phoneNumber.startsWith("3")) { // Potentially cellular without 03
                        try {
                            int n3val = Integer.parseInt(phoneNumber.substring(0, 3));
                            if (n3val >= 300 && n3val <= 350) {
                                newTelephonyTypeId = TelephonyTypeEnum.CELLULAR.getValue();
                            }
                        } catch (NumberFormatException | StringIndexOutOfBoundsException ignored) {
                        }
                    }
                } else if (len == 9) {
                    if ("60".equals(p2)) {
                        phoneNumber = phoneNumber.substring(len - 7);
                    }
                }
            } else { // OUTGOING - _es_Saliente logic
                if (len == 11 && phoneNumber.startsWith("03")) {
                    try {
                        int n3val = Integer.parseInt(phoneNumber.substring(1, 4)); // Get 3 digits after '0'
                        if (n3val >= 300 && n3val <= 350) {
                            phoneNumber = phoneNumber.substring(len - 10);
                            // newTelephonyTypeId = TelephonyTypeEnum.CELLULAR.getValue(); // Not set in PHP _es_Saliente
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        return new PhoneNumberTransformationResult(phoneNumber, !phoneNumber.equals(originalPhoneNumber), newTelephonyTypeId);
    }

    private static class PhoneNumberTransformationResult {
        private final String transformedNumber;
        private final boolean transformed;
        private final Long newTelephonyTypeId;

        public PhoneNumberTransformationResult(String transformedNumber, boolean transformed, Long newTelephonyTypeId) {
            this.transformedNumber = transformedNumber;
            this.transformed = transformed;
            this.newTelephonyTypeId = newTelephonyTypeId;
        }

        public String getTransformedNumber() {
            return transformedNumber;
        }

        public boolean isTransformed() {
            return transformed;
        }

        public Long getNewTelephonyTypeId() {
            return newTelephonyTypeId;
        }
    }
}
