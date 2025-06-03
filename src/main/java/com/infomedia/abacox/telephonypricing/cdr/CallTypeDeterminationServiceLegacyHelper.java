// File: com/infomedia/abacox/telephonypricing/cdr/CallTypeDeterminationServiceLegacyHelper.java
package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.CommunicationLocation;
import com.infomedia.abacox.telephonypricing.entity.Employee;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@Log4j2
@RequiredArgsConstructor
public class CallTypeDeterminationServiceLegacyHelper {

    private final EmployeeLookupService employeeLookupService;
    private final PbxSpecialRuleLookupService pbxSpecialRuleLookupService;

    /**
     * Logic adapted from PHP's `es_llamada_interna`
     * This is called if the parser (like Cisco CM 6.0) hasn't already definitively marked the call as internal.
     */
    public void checkIfPotentiallyInternal(CdrData cdrData, CommunicationLocation commLocation, ExtensionLimits limits) {
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
                        null, // Search globally for ranges if not tied to a specific commLocation context initially
                        cdrData.getDateTimeOrigination()
                );
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