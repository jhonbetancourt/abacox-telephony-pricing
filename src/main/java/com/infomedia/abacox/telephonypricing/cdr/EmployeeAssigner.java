package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.CallRecord;
import com.infomedia.abacox.telephonypricing.entity.CommunicationLocation;
import com.infomedia.abacox.telephonypricing.entity.Employee;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@Log4j2
@RequiredArgsConstructor
public class EmployeeAssigner {

    private final EmployeeLookupService employeeLookupService;
    private final CdrConfigService cdrConfigService;

    public void assignEmployee(CallRecord callRecord, RawCiscoCdrData rawData, CommunicationLocation commLocation, InternalExtensionLimitsDto limits) {
        Optional<Employee> employeeOpt = Optional.empty();
        ImdexAssignmentCause assignmentCause = ImdexAssignmentCause.NOT_ASSIGNED;
        List<String> ignoredAuthCodes = cdrConfigService.getIgnoredAuthCodes();

        String effectiveOriginator = rawData.getEffectiveOriginatingNumber();

        if (rawData.getAuthCodeDescription() != null && !rawData.getAuthCodeDescription().isEmpty() &&
                !ignoredAuthCodes.contains(rawData.getAuthCodeDescription())) {
            employeeOpt = employeeLookupService.findEmployeeByAuthCode(rawData.getAuthCodeDescription(), commLocation.getId());
            if (employeeOpt.isPresent()) {
                assignmentCause = ImdexAssignmentCause.BY_AUTH_CODE;
            }
        }

        if (employeeOpt.isEmpty() && effectiveOriginator != null && !effectiveOriginator.isEmpty()) {
            employeeOpt = employeeLookupService.findEmployeeByExtension(effectiveOriginator, commLocation.getId(), limits);
            if (employeeOpt.isPresent()) {
                assignmentCause = (employeeOpt.get().getId() == null) ? ImdexAssignmentCause.BY_EXTENSION_RANGE : ImdexAssignmentCause.BY_EXTENSION;
            }
        }

        if (employeeOpt.isEmpty() && rawData.getImdexTransferCause() != ImdexTransferCause.NO_TRANSFER &&
                rawData.getLastRedirectDn() != null && !rawData.getLastRedirectDn().isEmpty()) {

            Optional<Employee> redirectingEmployeeOpt = employeeLookupService.findEmployeeByExtension(rawData.getLastRedirectDn(), commLocation.getId(), limits);
            if (redirectingEmployeeOpt.isPresent()) {
                employeeOpt = redirectingEmployeeOpt;
                assignmentCause = ImdexAssignmentCause.BY_TRANSFER;
            }
        }

        employeeOpt.ifPresent(emp -> {
            callRecord.setEmployee(emp);
            callRecord.setEmployeeId(emp.getId());
        });
        callRecord.setAssignmentCause(assignmentCause.getValue());
    }
}
