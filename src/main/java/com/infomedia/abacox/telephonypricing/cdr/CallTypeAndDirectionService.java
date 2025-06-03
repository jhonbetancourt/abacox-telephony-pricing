// File: com/infomedia/abacox/telephonypricing/cdr/CallTypeAndDirectionService.java
package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.CommunicationLocation;
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
    private final CallTypeDeterminationServiceLegacyHelper legacyHelper; // For specific PHP logic parts

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
             legacyHelper.checkIfPotentiallyInternal(cdrData, commLocation, limits);
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
}