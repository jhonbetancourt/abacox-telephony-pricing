package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import com.infomedia.abacox.telephonypricing.entity.CommunicationLocation;
import com.infomedia.abacox.telephonypricing.entity.Employee;
import com.infomedia.abacox.telephonypricing.entity.ExtensionRange;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@Log4j2
@RequiredArgsConstructor
public class CallTypeAndDirectionService {

    private final IncomingCallProcessorService incomingCallProcessorService;
    private final OutgoingCallProcessorService outgoingCallProcessorService;
    private final InternalCallProcessorService internalCallProcessorService;
    private final EmployeeLookupService employeeLookupService;
    private final PbxSpecialRuleLookupService pbxSpecialRuleLookupService;

    /**
     * PHP equivalent: Orchestrates logic from CargarCDR's main loop after `evaluar_Formato`,
     * leading into `procesaEntrante` or `procesaSaliente`.
     * This method determines the initial nature of the call and then delegates.
     */
    public void processCall(CdrData cdrData, LineProcessingContext processingContext) {
        log.debug("Processing call for CDR: {}", cdrData.getCtlHash());

        // Store the initial direction determined by the parser
        CallDirection initialDirection = cdrData.getCallDirection();

        // Initial determination of internal call (PHP: es_llamada_interna)
        if (!cdrData.isInternalCall()) {
            checkIfPotentiallyInternal(cdrData, processingContext);
        }
        log.info("Initial call attributes - Direction: {}, Internal: {}", initialDirection, cdrData.isInternalCall());

        cdrData.setEffectiveDestinationNumber(cdrData.getFinalCalledPartyNumber());

        // An incoming call that is found to be internal must be processed as internal, not as incoming.
        if (cdrData.isInternalCall()) {
            // PHP: procesaEntrante -> info_interna -> InvertirLlamada
            if (initialDirection == CallDirection.INCOMING) {
                log.info("Re-classifying INCOMING call as INTERNAL for processing. CDR: {}", cdrData.getCtlHash());
                CdrUtil.swapFull(cdrData, true); // Full swap including trunks
                cdrData.setCallDirection(CallDirection.OUTGOING); // It's now treated as an outgoing internal call
            }
            internalCallProcessorService.processInternal(cdrData, processingContext, false);
        } else if (initialDirection == CallDirection.INCOMING) {
            incomingCallProcessorService.processIncoming(cdrData, processingContext);
        } else { // OUTGOING
            outgoingCallProcessorService.processOutgoing(cdrData, processingContext, false);
        }

        log.info("Finished processing call. Final Direction: {}, Internal: {}, TelephonyType: {}",
                cdrData.getCallDirection(), cdrData.isInternalCall(), cdrData.getTelephonyTypeId());
    }

    /**
     * Logic adapted from PHP's `es_llamada_interna`
     * This is called if the parser (like Cisco CM 6.0) hasn't already definitively marked the call as internal.
     */
    private void checkIfPotentiallyInternal(CdrData cdrData, LineProcessingContext lineProcessingContext) {
        log.debug("Checking if call is potentially internal. Calling: '{}', FinalCalled: '{}'",
                cdrData.getCallingPartyNumber(), cdrData.getFinalCalledPartyNumber());
        ExtensionLimits limits = lineProcessingContext.getCommLocationExtensionLimits();
        CommunicationLocation commLocation = lineProcessingContext.getCommLocation();

        if (CdrUtil.isPossibleExtension(cdrData.getCallingPartyNumber(), limits)) {
            log.debug("Calling party '{}' is a possible extension. Checking destination for internal call.", cdrData.getCallingPartyNumber());
            String destinationForInternalCheck = CdrUtil.cleanPhoneNumber(cdrData.getFinalCalledPartyNumber(), null, false).getCleanedNumber();
            log.debug("Cleaned destination for internal check: {}", destinationForInternalCheck);

            Optional<String> pbxInternalTransformed = pbxSpecialRuleLookupService.applyPbxSpecialRule(
                    destinationForInternalCheck, commLocation.getDirectory(), PbxRuleDirection.INTERNAL.getValue()
            );
            if (pbxInternalTransformed.isPresent()) {
                destinationForInternalCheck = pbxInternalTransformed.get();
                cdrData.setInternalCheckPbxTransformedDest(destinationForInternalCheck);
                log.debug("Destination transformed by PBX internal rule: {}", destinationForInternalCheck);
            }

            if ((destinationForInternalCheck.length() == 1 && destinationForInternalCheck.matches("\\d")) ||
                    CdrUtil.isPossibleExtension(destinationForInternalCheck, limits)) {
                cdrData.setInternalCall(true);
                log.debug("Marked as internal call based on destination '{}' format/possibility.", destinationForInternalCheck);
            } else if (destinationForInternalCheck.matches("\\d+") &&
                    (!destinationForInternalCheck.startsWith("0") || destinationForInternalCheck.equals("0")) &&
                    !destinationForInternalCheck.isEmpty()) {
                log.debug("Destination '{}' is numeric, not starting with 0 (or is '0'). Checking extension ranges.", destinationForInternalCheck);
                Optional<Employee> employeeFromRange = employeeLookupService.findEmployeeByExtensionRange(
                        destinationForInternalCheck,
                        null, lineProcessingContext.getExtensionRanges());
                if (employeeFromRange.isPresent()) {
                    cdrData.setInternalCall(true);
                    log.debug("Marked as internal call based on destination '{}' matching an extension range.", destinationForInternalCheck);
                } else {
                    log.debug("Destination '{}' did not match any extension range.", destinationForInternalCheck);
                }
            } else {
                log.debug("Destination '{}' not identified as internal by format or range.", destinationForInternalCheck);
            }
        } else {
            log.debug("Calling party '{}' is not a possible extension. Not an internal call.", cdrData.getCallingPartyNumber());
        }
    }
}