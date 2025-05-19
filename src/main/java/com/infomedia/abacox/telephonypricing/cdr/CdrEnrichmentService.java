// File: com/infomedia/abacox/telephonypricing/cdr/CdrEnrichmentService.java
package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.CommunicationLocation;
import com.infomedia.abacox.telephonypricing.entity.Employee;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

@Service
@Log4j2
@RequiredArgsConstructor
public class CdrEnrichmentService {

    private final CallTypeDeterminationService callTypeDeterminationService;
    private final EmployeeLookupService employeeLookupService;
    private final TariffCalculationService tariffCalculationService;
    private final CdrConfigService appConfigService;
    private final PhoneNumberTransformationService phoneNumberTransformationService;
    private final PbxSpecialRuleLookupService pbxSpecialRuleLookupService;
    private final TelephonyTypeLookupService telephonyTypeLookupService; // Keep for default names if needed

    public CdrData enrichCdr(CdrData cdrData, CommunicationLocation commLocation) {
        if (cdrData == null || cdrData.isMarkedForQuarantine()) {
            log.warn("CDR is null or already marked for quarantine. Skipping enrichment. CDR: {}", cdrData != null ? cdrData.getRawCdrLine() : "NULL");
            return cdrData;
        }
        log.info("Starting enrichment for CDR: {}", cdrData.getRawCdrLine());
        cdrData.setCommLocationId(commLocation.getId());

        try {
            // Initial determination of direction and internal status (might be refined)
            callTypeDeterminationService.determineCallTypeAndDirection(cdrData, commLocation);
            log.debug("After call type determination: Direction={}, Internal={}, TelephonyType={}",
                    cdrData.getCallDirection(), cdrData.isInternalCall(), cdrData.getTelephonyTypeId());

            String searchExtForEmployee;
            String searchAuthCodeForEmployee;

            if (cdrData.getCallDirection() == CallDirection.INCOMING) {
                // After parser swap, callingPartyNumber is our extension
                searchExtForEmployee = cdrData.getCallingPartyNumber();
                searchAuthCodeForEmployee = null; // Auth codes typically not used for the receiving leg of an incoming call
                log.debug("Incoming call. Searching employee by (our) extension: {}", searchExtForEmployee);
            } else { // OUTGOING or internal treated as outgoing initially
                searchExtForEmployee = cdrData.getCallingPartyNumber();
                searchAuthCodeForEmployee = cdrData.getAuthCodeDescription();
                log.debug("Outgoing/Internal call. Searching employee by extension: {}, authCode: {}", searchExtForEmployee, searchAuthCodeForEmployee);
            }

            Employee foundEmployee = employeeLookupService.findEmployeeByExtensionOrAuthCode(
                            searchExtForEmployee,
                            searchAuthCodeForEmployee,
                            commLocation.getId(), // Context for non-global search
                            cdrData.getDateTimeOrigination())
                    .orElse(null);

            if (foundEmployee != null) {
                cdrData.setEmployeeId(foundEmployee.getId());
                cdrData.setEmployee(foundEmployee); // For convenience
                log.info("Found employee: ID={}, Ext={}", foundEmployee.getId(), foundEmployee.getExtension());

                boolean authCodeProvided = searchAuthCodeForEmployee != null && !searchAuthCodeForEmployee.isEmpty();
                boolean authCodeMatched = authCodeProvided &&
                                          foundEmployee.getAuthCode() != null &&
                                          foundEmployee.getAuthCode().equalsIgnoreCase(searchAuthCodeForEmployee);
                boolean isIgnoredAuthCodeType = authCodeProvided && appConfigService.getIgnoredAuthCodeDescriptions().stream()
                                                                    .anyMatch(desc -> desc.equalsIgnoreCase(searchAuthCodeForEmployee));

                if (authCodeMatched) {
                    cdrData.setAssignmentCause(AssignmentCause.AUTH_CODE);
                } else if (authCodeProvided && !isIgnoredAuthCodeType) {
                    cdrData.setAssignmentCause(AssignmentCause.IGNORED_AUTH_CODE);
                } else {
                    cdrData.setAssignmentCause(AssignmentCause.EXTENSION);
                }

                if (foundEmployee.getId() == null && // Conceptual employee from range
                    cdrData.getDurationSeconds() != null && cdrData.getDurationSeconds() > 0 &&
                    appConfigService.createEmployeesAutomaticallyFromRange()) {
                    cdrData.setAssignmentCause(AssignmentCause.RANGES);
                }
                log.debug("Employee assignment cause: {}", cdrData.getAssignmentCause());

            } else {
                 cdrData.setAssignmentCause(AssignmentCause.NOT_ASSIGNED);
                 log.warn("Employee not found for Ext: {}, AuthCode: {}", searchExtForEmployee, searchAuthCodeForEmployee);
            }

            // For internal calls, try to find the destination employee
            if (cdrData.isInternalCall() && cdrData.getEffectiveDestinationNumber() != null) {
                 employeeLookupService.findEmployeeByExtensionOrAuthCode(
                                 cdrData.getEffectiveDestinationNumber(), null, // No auth code for destination lookup
                                 null, // Global search for destination extension
                                 cdrData.getDateTimeOrigination())
                    .ifPresent(destEmployee -> {
                        cdrData.setDestinationEmployeeId(destEmployee.getId());
                        cdrData.setDestinationEmployee(destEmployee);
                        log.debug("Internal call destination employee found: ID={}, Ext={}", destEmployee.getId(), destEmployee.getExtension());
                    });
            }

            // PHP: if (!ExtensionEncontrada($arreglo_fun) && !$es_interna && isset($info['funcionario_redir']) && $info['funcionario_redir']['comid'] == $COMUBICACION_ID)
            // Handle assignment by transfer if primary employee not found and it's not an internal call
            if (cdrData.getEmployeeId() == null && !cdrData.isInternalCall() &&
                cdrData.getLastRedirectDn() != null && !cdrData.getLastRedirectDn().isEmpty()) {
                Employee redirEmployee = employeeLookupService.findEmployeeByExtensionOrAuthCode(
                                cdrData.getLastRedirectDn(), null,
                                commLocation.getId(), // Transferring employee must be in the same commLocation
                                cdrData.getDateTimeOrigination())
                        .orElse(null);
                if (redirEmployee != null &&
                    redirEmployee.getCommunicationLocation() != null &&
                    Objects.equals(redirEmployee.getCommunicationLocation().getId(), commLocation.getId())) {
                    cdrData.setEmployeeId(redirEmployee.getId());
                    cdrData.setEmployee(redirEmployee);
                    cdrData.setAssignmentCause(AssignmentCause.TRANSFER);
                    log.info("Assigned call to redirecting employee (due to transfer): ID={}, Ext={}", redirEmployee.getId(), redirEmployee.getExtension());
                }
            }

            // Process specific logic based on final direction
            if (cdrData.getCallDirection() == CallDirection.INCOMING) {
                processIncomingLogic(cdrData, commLocation, callTypeDeterminationService.getExtensionLimits(commLocation));
            }
            // For outgoing, effectiveDestinationNumber is already set by determineCallTypeAndDirection or parser.

            // Tariffing and final checks
            if (cdrData.getDurationSeconds() != null && cdrData.getDurationSeconds() <= appConfigService.getMinCallDurationForTariffing()) {
                if (cdrData.getTelephonyTypeId() != null &&
                    cdrData.getTelephonyTypeId() > 0 &&
                    cdrData.getTelephonyTypeId() != TelephonyTypeEnum.ERRORS.getValue()) {
                    log.info("Call duration {}s <= min. Setting type to NO_CONSUMPTION.", cdrData.getDurationSeconds());
                    cdrData.setTelephonyTypeId(TelephonyTypeEnum.NO_CONSUMPTION.getValue());
                    cdrData.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(TelephonyTypeEnum.NO_CONSUMPTION.getValue()));
                    cdrData.setBilledAmount(BigDecimal.ZERO);
                    cdrData.setPricePerMinute(BigDecimal.ZERO);
                    cdrData.setInitialPricePerMinute(BigDecimal.ZERO);
                }
            } else if (cdrData.getTelephonyTypeId() != null &&
                       cdrData.getTelephonyTypeId() != TelephonyTypeEnum.ERRORS.getValue() &&
                       cdrData.getTelephonyTypeId() != TelephonyTypeEnum.NO_CONSUMPTION.getValue()) {
                log.debug("Proceeding to tariff calculation for type: {}", cdrData.getTelephonyTypeId());
                tariffCalculationService.calculateTariffs(cdrData, commLocation);
            } else {
                log.debug("Skipping tariff calculation. Duration: {}, Type: {}", cdrData.getDurationSeconds(), cdrData.getTelephonyTypeId());
                 if (cdrData.getBilledAmount() == null) cdrData.setBilledAmount(BigDecimal.ZERO);
                 if (cdrData.getPricePerMinute() == null) cdrData.setPricePerMinute(BigDecimal.ZERO);
                 if (cdrData.getInitialPricePerMinute() == null) cdrData.setInitialPricePerMinute(BigDecimal.ZERO);
            }

            // Final transfer info cleanup (PHP: if ($ext_transfer !== '' && ($ext_transfer === $extension || $ext_transfer === $tel_destino)...)
            if (cdrData.getTransferCause() != TransferCause.NONE &&
                cdrData.getLastRedirectDn() != null && !cdrData.getLastRedirectDn().isEmpty()) {
                cdrData.setEmployeeTransferExtension(cdrData.getLastRedirectDn());
                boolean transferToSelfOrOtherParty = false;
                String currentPartyExtension = (cdrData.getCallDirection() == CallDirection.INCOMING) ?
                                             cdrData.getCallingPartyNumber() : // Our extension
                                             cdrData.getFinalCalledPartyNumber();    // Destination for outgoing

                String otherPartyExtension = (cdrData.getCallDirection() == CallDirection.INCOMING) ?
                                           cdrData.getFinalCalledPartyNumber() :    // External for incoming
                                           cdrData.getCallingPartyNumber(); // Our extension for outgoing

                if (Objects.equals(cdrData.getLastRedirectDn(), currentPartyExtension) ||
                    Objects.equals(cdrData.getLastRedirectDn(), otherPartyExtension)) {
                    transferToSelfOrOtherParty = true;
                }

                if (transferToSelfOrOtherParty &&
                    cdrData.getTransferCause() != TransferCause.CONFERENCE &&
                    cdrData.getTransferCause() != TransferCause.CONFERENCE_NOW &&
                    cdrData.getTransferCause() != TransferCause.PRE_CONFERENCE_NOW) {
                    log.debug("Clearing transfer info as it's a transfer to self/current party for non-conference. Original transfer ext: {}", cdrData.getLastRedirectDn());
                    cdrData.setEmployeeTransferExtension(null);
                    cdrData.setTransferCause(TransferCause.NONE);
                } else {
                    log.debug("Finalized transfer info. Transfer Ext: {}, Cause: {}", cdrData.getEmployeeTransferExtension(), cdrData.getTransferCause());
                }
            }

        } catch (Exception e) {
            log.error("Error during CDR enrichment for line: {}", cdrData.getRawCdrLine(), e);
            cdrData.setMarkedForQuarantine(true);
            cdrData.setQuarantineReason("Enrichment failed: " + e.getMessage());
            cdrData.setQuarantineStep("enrichCdr_UnhandledException");
        }
        log.info("Finished enrichment for CDR: {}. Billed Amount: {}, Type: {}, Operator: {}",
                 cdrData.getRawCdrLine(), cdrData.getBilledAmount(), cdrData.getTelephonyTypeName(), cdrData.getOperatorId());
        return cdrData;
    }

    private void processIncomingLogic(CdrData cdrData, CommunicationLocation commLocation, ExtensionLimits limits) {
        log.debug("Processing INCOMING logic for CDR: {}", cdrData.getRawCdrLine());

        // If it's an internal call that was identified as incoming, it should have been flipped by determineCallTypeAndDirection.
        // If it's still INCOMING and INTERNAL here, it's a special case that needs careful handling,
        // potentially re-running call type determination or specific internal processing.
        // For now, assume if it's INCOMING, it's an external call to one of our extensions.
        if (cdrData.isInternalCall()) {
            log.warn("processIncomingLogic called for a CdrData still marked as internal. This might indicate a flow issue. Proceeding as external incoming for now.");
            // It might be better to call processInternalCallLogic here if this state is valid.
            // However, the main determineCallTypeAndDirection should ideally resolve this.
        }

        String externalNumber = cdrData.getFinalCalledPartyNumber(); // This is the actual external number after parser swap
        cdrData.setEffectiveDestinationNumber(externalNumber); // Initialize effective number for tariffing
        log.debug("Incoming call. External Number (finalCalledParty): {}, Our Extension (callingParty): {}", externalNumber, cdrData.getCallingPartyNumber());

        Optional<String> pbxTransformedExternal = pbxSpecialRuleLookupService.applyPbxSpecialRule(
                externalNumber, commLocation.getDirectory(), 1 // 1 for incoming
        );
        if (pbxTransformedExternal.isPresent()) {
            log.debug("External Number '{}' transformed by PBX incoming rule to '{}'", externalNumber, pbxTransformedExternal.get());
            cdrData.setOriginalDialNumberBeforePbxIncoming(externalNumber);
            externalNumber = pbxTransformedExternal.get();
            cdrData.setPbxSpecialRuleAppliedInfo("PBX Incoming Rule: " + cdrData.getFinalCalledPartyNumber() + " -> " + externalNumber);
        }

        TransformationResult cmeTransformedExternal = phoneNumberTransformationService.transformIncomingNumberCME(
                externalNumber, commLocation.getIndicator().getOriginCountryId()
        );
        if (cmeTransformedExternal.isTransformed()) {
            log.debug("External Number '{}' transformed by CME rule to '{}'", externalNumber, cmeTransformedExternal.getTransformedNumber());
            cdrData.setOriginalDialNumberBeforeCMETransform(externalNumber);
            externalNumber = cmeTransformedExternal.getTransformedNumber();
            if (cmeTransformedExternal.getNewTelephonyTypeId() != null) {
                cdrData.setHintedTelephonyTypeIdFromTransform(cmeTransformedExternal.getNewTelephonyTypeId());
            }
        }

        // Update the finalCalledPartyNumber (external number) and effectiveDestinationNumber with the (potentially) transformed external number
        cdrData.setFinalCalledPartyNumber(externalNumber);
        cdrData.setEffectiveDestinationNumber(externalNumber); // This will be used by TariffCalculationService

        // The main TelephonyType, OperatorId, IndicatorId for the CallRecord
        // will be set by TariffCalculationService based on this (external) number.
        log.debug("Finished processing INCOMING logic (transformations only). External number for tariffing: {}", cdrData.getEffectiveDestinationNumber());
    }
}