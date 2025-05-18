package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.CommunicationLocation;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

@Service
@Log4j2
public class CdrEnrichmentService {

    private final CallTypeDeterminationService callTypeDeterminationService;
    private final EmployeeLookupService employeeLookupService;
    private final TariffCalculationService tariffCalculationService;
    private final CdrConfigService appConfigService;

    public CdrEnrichmentService(CallTypeDeterminationService callTypeDeterminationService,
                                EmployeeLookupService employeeLookupService,
                                TariffCalculationService tariffCalculationService,
                                CdrConfigService appConfigService) {
        this.callTypeDeterminationService = callTypeDeterminationService;
        this.employeeLookupService = employeeLookupService;
        this.tariffCalculationService = tariffCalculationService;
        this.appConfigService = appConfigService;
    }

    public CdrData enrichCdr(CdrData cdrData, CommunicationLocation commLocation) {
        if (cdrData == null || cdrData.isMarkedForQuarantine()) {
            return cdrData;
        }
        cdrData.setCommLocationId(commLocation.getId());

        try {
            // 1. Determine Call Type (Internal, Incoming, Outgoing) and apply initial transformations
            callTypeDeterminationService.determineCallTypeAndDirection(cdrData, commLocation);

            // 2. Assign Employees
            // PHP's ObtenerFuncionario_Arreglo logic
            // Simplified: assign based on current extension/authCode
            String searchExt = cdrData.getCallDirection() == CallDirection.INCOMING ? cdrData.getFinalCalledPartyNumber() : cdrData.getCallingPartyNumber();
            String searchAuthCode = cdrData.getCallDirection() == CallDirection.OUTGOING ? cdrData.getAuthCodeDescription() : null;

            employeeLookupService.findEmployeeByExtensionOrAuthCode(searchExt, searchAuthCode, commLocation.getId(), cdrData.getDateTimeOrigination())
                .ifPresent(employee -> {
                    cdrData.setEmployeeId(employee.getId());
                    cdrData.setEmployee(employee); // For convenience
                    if (searchAuthCode != null && !searchAuthCode.isEmpty() && employee.getAuthCode() != null && employee.getAuthCode().equalsIgnoreCase(searchAuthCode)) {
                        cdrData.setAssignmentCause(AssignmentCause.AUTH_CODE);
                    } else {
                        cdrData.setAssignmentCause(AssignmentCause.EXTENSION);
                    }
                });
            
            // Handle destination employee for internal calls
            if (cdrData.isInternalCall() && cdrData.getEffectiveDestinationNumber() != null) {
                 employeeLookupService.findEmployeeByExtensionOrAuthCode(cdrData.getEffectiveDestinationNumber(), null, commLocation.getId(), cdrData.getDateTimeOrigination())
                    .ifPresent(destEmployee -> {
                        cdrData.setDestinationEmployeeId(destEmployee.getId());
                        cdrData.setDestinationEmployee(destEmployee);
                    });
            }


            // 3. Calculate Tariffs (if not an error call or no consumption)
            if (cdrData.getTelephonyTypeId() != null && 
                cdrData.getTelephonyTypeId() != TelephonyTypeEnum.ERRORS.getValue() &&
                cdrData.getTelephonyTypeId() != TelephonyTypeEnum.NO_CONSUMPTION.getValue()) {
                
                tariffCalculationService.calculateTariffs(cdrData, commLocation);
            } else if (cdrData.getDurationSeconds() <= appConfigService.getMinCallDurationForTariffing() &&
                       (cdrData.getTelephonyTypeId() == null || cdrData.getTelephonyTypeId() == 0L || cdrData.getTelephonyTypeId() == TelephonyTypeEnum.UNKNOWN.getValue())) {
                // If duration is zero or less than min, and not already an error, mark as NO_CONSUMPTION
                // PHP: if ($tiempo <= 0 && $tipotele_id > 0 && $tipotele_id != _TIPOTELE_ERRORES) $tipotele_id = _TIPOTELE_SINCONSUMO;
                if (cdrData.getTelephonyTypeId() == null || cdrData.getTelephonyTypeId() != TelephonyTypeEnum.ERRORS.getValue()){
                    cdrData.setTelephonyTypeId(TelephonyTypeEnum.NO_CONSUMPTION.getValue());
                    cdrData.setTelephonyTypeName("No Consumption"); // Or lookup from DB
                }
            }


            // 4. Handle Transfers (already partially done in CiscoCm60Processor, refine here if needed)
            // The transferCause is set. employeeTransferExtension might need to be populated.
            if (cdrData.getTransferCause() != TransferCause.NONE && cdrData.getLastRedirectDn() != null) {
                cdrData.setEmployeeTransferExtension(cdrData.getLastRedirectDn());
            }


        } catch (Exception e) {
            log.error("Error during CDR enrichment for line: {}", cdrData.getRawCdrLine(), e);
            cdrData.setMarkedForQuarantine(true);
            cdrData.setQuarantineReason("Enrichment failed: " + e.getMessage());
            cdrData.setQuarantineStep("enrichCdr");
        }

        return cdrData;
    }
}