// File: com/infomedia/abacox/telephonypricing/cdr/CallTypeAndDirectionService.java
package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.CommunicationLocation;
import com.infomedia.abacox.telephonypricing.entity.Employee;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@Log4j2
@RequiredArgsConstructor
public class CallTypeAndDirectionService {
    private final IncomingCallProcessorService incomingCallProcessorService;
    private final OutgoingCallProcessorService outgoingCallProcessorService;
    private final EmployeeLookupService employeeLookupService;
    private final PbxSpecialRuleLookupService pbxSpecialRuleLookupService;

    /**
     * PHP equivalent: Orchestrates logic from CargarCDR's main loop after `evaluar_Formato`,
     * leading into `procesaEntrante` or `procesaSaliente`.
     * This method determines the initial nature of the call and then delegates.
     */
    public void processCall(CdrData cdrData, CommunicationLocation commLocation, ExtensionLimits limits) {
        log.debug("Processing call for CDR: {}", cdrData.getCtlHash());

        // Initial determination of internal call (PHP: es_llamada_interna)
        // The Cisco CM 6.0 parser already sets cdrData.isInternalCall() based on partitions.
        // If it wasn't set by parser, or for other plant types, this logic would be more complex.
        // For CM 6.0, we trust the parser's initial assessment.
        // If further checks are needed (like PHP's es_llamada_interna for non-partition based plants):
        if (!cdrData.isInternalCall()) { // If parser didn't mark it internal, do PHP-like checks
             checkIfPotentiallyInternal(cdrData, commLocation, limits);
        }
        log.info("Initial call attributes - Direction: {}, Internal: {}", cdrData.getCallDirection(), cdrData.isInternalCall());

        // Set effective destination number before processing logic.
        // This will be refined within processIncomingLogic/processOutgoingLogic.
        cdrData.setEffectiveDestinationNumber(cdrData.getFinalCalledPartyNumber());

        if (cdrData.getCallDirection() == CallDirection.INCOMING) {
            incomingCallProcessorService.processIncoming(cdrData, commLocation, limits);
        } else { // OUTGOING or internal initially parsed as outgoing
            outgoingCallProcessorService.processOutgoing(cdrData, commLocation, limits, false);
        }
        log.info("Finished processing call. Final Direction: {}, Internal: {}, TelephonyType: {}",
                 cdrData.getCallDirection(), cdrData.isInternalCall(), cdrData.getTelephonyTypeId());
    }

    /**
     * Logic adapted from PHP's `es_llamada_interna`
     * This is called if the parser (like Cisco CM 6.0) hasn't already definitively marked the call as internal.
     */
    private void checkIfPotentiallyInternal(CdrData cdrData, CommunicationLocation commLocation, ExtensionLimits limits) {
        log.debug("Checking if call is potentially internal. Calling: '{}', FinalCalled: '{}'",
                cdrData.getCallingPartyNumber(), cdrData.getFinalCalledPartyNumber());

        if (employeeLookupService.isPossibleExtension(cdrData.getCallingPartyNumber(), limits)) {
            log.debug("Calling party '{}' is a possible extension. Checking destination for internal call.", cdrData.getCallingPartyNumber());
            String destinationForInternalCheck = CdrUtil.cleanPhoneNumber(cdrData.getFinalCalledPartyNumber(), null, false);
            log.debug("Cleaned destination for internal check: {}", destinationForInternalCheck);

            // PHP: $telefono_eval = evaluarPBXEspecial($link, $destino, $directorio, $cliente, 3); // internas
            Optional<String> pbxInternalTransformed = pbxSpecialRuleLookupService.applyPbxSpecialRule(
                    destinationForInternalCheck, commLocation.getDirectory(), PbxRuleDirection.INTERNAL.getValue()
            );
            if (pbxInternalTransformed.isPresent()) {
                destinationForInternalCheck = pbxInternalTransformed.get();
                cdrData.setInternalCheckPbxTransformedDest(destinationForInternalCheck); // Store for reference
                log.debug("Destination transformed by PBX internal rule: {}", destinationForInternalCheck);
            }

            // PHP: $esinterna = ($len_destino == 1 || ExtensionPosible($destino) || ExtensionEspecial($destino));
            // PHP: if (!$esinterna && $no_inicia_cero && $es_numerico && $destino != '' ) { $retornar = Validar_RangoExt(...); $esinterna = $retornar['nuevo']; }
            if ((destinationForInternalCheck.length() == 1 && destinationForInternalCheck.matches("\\d")) ||
                    employeeLookupService.isPossibleExtension(destinationForInternalCheck, limits)) {
                cdrData.setInternalCall(true);
                log.debug("Marked as internal call based on destination '{}' format/possibility.", destinationForInternalCheck);
            } else if (destinationForInternalCheck.matches("\\d+") &&
                    (!destinationForInternalCheck.startsWith("0") || destinationForInternalCheck.equals("0")) && // PHP: ExtensionValida($destino, true)
                    !destinationForInternalCheck.isEmpty()) {
                log.debug("Destination '{}' is numeric, not starting with 0 (or is '0'). Checking extension ranges.", destinationForInternalCheck);
                Optional<Employee> employeeFromRange = employeeLookupService.findEmployeeByExtensionRange(
                        destinationForInternalCheck,
                        null); // Search globally for ranges if not tied to a specific commLocation context initially
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