
package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;


@Service
@RequiredArgsConstructor
@Log4j2
public class EnrichmentService {

    private final LookupService lookupService;
    private final CdrConfigService cdrConfigService;

    public void enrichCallRecord(CallRecord callRecord, RawCdrData rawData, CommunicationLocation commLocation) {
        InternalExtensionLimitsDto internalLimits = cdrConfigService.getInternalExtensionLimits(
            commLocation.getIndicator() != null ? commLocation.getIndicator().getOriginCountryId() : null,
            commLocation.getId()
        );

        assignEmployee(callRecord, rawData, commLocation, internalLimits);
        determineCallTypeAndPricing(callRecord, rawData, commLocation, internalLimits);

        if (callRecord.getDuration() != null && callRecord.getDuration() <= cdrConfigService.getMinCallDurationForProcessing() &&
            callRecord.getTelephonyTypeId() != null &&
            !Objects.equals(callRecord.getTelephonyTypeId(), TelephonyTypeConstants.ERRORES) &&
            !Objects.equals(callRecord.getTelephonyTypeId(), TelephonyTypeConstants.SIN_CONSUMO)) {
            
            log.debug("Call duration is zero or below min, marking as SIN_CONSUMO. CDR Hash: {}", callRecord.getCdrHash());
            setTelephonyTypeAndOperator(callRecord, TelephonyTypeConstants.SIN_CONSUMO,
                commLocation.getIndicator() != null ? commLocation.getIndicator().getOriginCountryId() : null,
                null, true);
            callRecord.setBilledAmount(BigDecimal.ZERO);
            callRecord.setPricePerMinute(BigDecimal.ZERO);
            callRecord.setInitialPrice(BigDecimal.ZERO);
        }
    }

    private void assignEmployee(CallRecord callRecord, RawCdrData rawData, CommunicationLocation commLocation, InternalExtensionLimitsDto limits) {
        Optional<Employee> employeeOpt = Optional.empty();
        ImdexAssignmentCause assignmentCause = ImdexAssignmentCause.NOT_ASSIGNED;
        List<String> ignoredAuthCodes = cdrConfigService.getIgnoredAuthCodes();

        String effectiveOriginator = rawData.getEffectiveOriginatingNumber();

        if (rawData.getAuthCodeDescription() != null && !rawData.getAuthCodeDescription().isEmpty() &&
            !ignoredAuthCodes.contains(rawData.getAuthCodeDescription())) {
            employeeOpt = lookupService.findEmployeeByAuthCode(rawData.getAuthCodeDescription(), commLocation.getId());
            if (employeeOpt.isPresent()) {
                assignmentCause = ImdexAssignmentCause.BY_AUTH_CODE;
            }
        }

        if (employeeOpt.isEmpty() && effectiveOriginator != null && !effectiveOriginator.isEmpty()) {
            employeeOpt = lookupService.findEmployeeByExtension(effectiveOriginator, commLocation.getId(), limits);
            if (employeeOpt.isPresent()) {
                 assignmentCause = (employeeOpt.get().getId() == null) ? ImdexAssignmentCause.BY_EXTENSION_RANGE : ImdexAssignmentCause.BY_EXTENSION;
            }
        }
        
        if (employeeOpt.isEmpty() && rawData.getImdexTransferCause() != ImdexTransferCause.NO_TRANSFER &&
            rawData.getLastRedirectDn() != null && !rawData.getLastRedirectDn().isEmpty()) {
            
             Optional<Employee> redirectingEmployeeOpt = lookupService.findEmployeeByExtension(rawData.getLastRedirectDn(), commLocation.getId(), limits);
             if (redirectingEmployeeOpt.isPresent()) {
                 employeeOpt = redirectingEmployeeOpt;
                 assignmentCause = ImdexAssignmentCause.BY_TRANSFER;
             }
        }

        employeeOpt.ifPresent(emp -> {
            callRecord.setEmployee(emp);
            callRecord.setEmployeeId(emp.getId());
            // If employee is virtual (from range), ensure extension is set on CallRecord
            if (emp.getId() == null && callRecord.getEmployeeExtension() == null) {
                callRecord.setEmployeeExtension(emp.getExtension());
            }
        });
        callRecord.setAssignmentCause(assignmentCause.getValue());
    }


    private void determineCallTypeAndPricing(CallRecord callRecord, RawCdrData rawData, CommunicationLocation commLocation, InternalExtensionLimitsDto limits) {
        String effectiveDialedNumber = rawData.getEffectiveDestinationNumber();
        
        effectiveDialedNumber = applyPbxSpecialRules(effectiveDialedNumber, commLocation, callRecord.isIncoming());
        callRecord.setDial(CdrHelper.cleanPhoneNumber(effectiveDialedNumber));

        setDefaultErrorTelephonyType(callRecord); 

        if (effectiveDialedNumber == null || effectiveDialedNumber.isEmpty()) {
            log.warn("Effective dialed number is empty for CDR hash: {}", callRecord.getCdrHash());
            return;
        }

        Long originCountryId = commLocation.getIndicator() != null ? commLocation.getIndicator().getOriginCountryId() : null;
        if (originCountryId == null) {
            log.error("Origin Country ID is null for commLocationId: {}. Cannot proceed with pricing.", commLocation.getId());
            return;
        }

        if (handleSpecialServices(callRecord, effectiveDialedNumber, commLocation, originCountryId)) {
            return;
        }

        InternalCallTypeResultDto internalCallResult = evaluateInternalCallType(
            rawData.getEffectiveOriginatingNumber(),
            effectiveDialedNumber, 
            commLocation, limits, originCountryId, rawData.getDateTimeOrigination(),
            rawData.getEffectiveOriginatingPartition(), 
            rawData.getEffectiveDestinationPartition()
        );

        if (internalCallResult != null) {
            if (internalCallResult.isIgnoreCall()) {
                log.info("Ignoring internal call as per logic (e.g., inter-plant global): {}", callRecord.getCdrHash());
                setTelephonyTypeAndOperator(callRecord, TelephonyTypeConstants.SIN_CONSUMO, originCountryId, null, true);
                callRecord.setBilledAmount(BigDecimal.ZERO);
                callRecord.setPricePerMinute(BigDecimal.ZERO);
                callRecord.setInitialPrice(BigDecimal.ZERO);
                return;
            }
            setTelephonyTypeAndOperator(callRecord, internalCallResult.getTelephonyType().getId(), originCountryId, internalCallResult.getDestinationIndicator(), true);
            callRecord.setOperator(internalCallResult.getOperator()); 
            callRecord.setOperatorId(internalCallResult.getOperator() != null ? internalCallResult.getOperator().getId() : null);
            
            // Set origin employee if it was determined by internal call logic (e.g. incoming internal)
            if (internalCallResult.getOriginEmployee() != null && callRecord.getEmployeeId() == null) {
                 callRecord.setEmployee(internalCallResult.getOriginEmployee());
                 callRecord.setEmployeeId(internalCallResult.getOriginEmployee().getId());
                 if (internalCallResult.getOriginEmployee().getId() == null) { // Virtual employee from range
                     callRecord.setEmployeeExtension(internalCallResult.getOriginEmployee().getExtension());
                 }
            }
            callRecord.setDestinationEmployee(internalCallResult.getDestinationEmployee());
            callRecord.setDestinationEmployeeId(internalCallResult.getDestinationEmployee() != null ? internalCallResult.getDestinationEmployee().getId() : null);
            
            if (internalCallResult.isIncomingInternal() && !callRecord.isIncoming()) {
                 callRecord.setIncoming(true);
                 String tempTrunk = callRecord.getTrunk();
                 callRecord.setTrunk(callRecord.getInitialTrunk());
                 callRecord.setInitialTrunk(tempTrunk);
            }

            Optional<Prefix> internalPrefixOpt = lookupService.findInternalPrefixForType(internalCallResult.getTelephonyType().getId(), originCountryId);
            if (internalPrefixOpt.isPresent()) {
                Prefix internalPrefix = internalPrefixOpt.get();
                BigDecimal priceExVat = internalPrefix.getBaseValue() != null ? internalPrefix.getBaseValue() : BigDecimal.ZERO;
                BigDecimal vatRate = internalPrefix.getVatValue() != null ? internalPrefix.getVatValue() : BigDecimal.ZERO;
                boolean vatIncluded = internalPrefix.isVatIncluded();

                if (vatIncluded && vatRate.compareTo(BigDecimal.ZERO) > 0) {
                    priceExVat = priceExVat.divide(BigDecimal.ONE.add(vatRate.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP)), 4, RoundingMode.HALF_UP);
                }
                callRecord.setInitialPrice(priceExVat.setScale(4, RoundingMode.HALF_UP));

                BigDecimal priceWithVat = priceExVat;
                if (vatRate.compareTo(BigDecimal.ZERO) > 0) {
                    priceWithVat = priceExVat.multiply(BigDecimal.ONE.add(vatRate.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP))).setScale(4, RoundingMode.HALF_UP);
                }
                callRecord.setPricePerMinute(priceWithVat.setScale(4, RoundingMode.HALF_UP)); 
                
                int durationInSeconds = callRecord.getDuration() != null ? callRecord.getDuration() : 0;
                long billedUnits = CdrHelper.duracionMinuto(durationInSeconds, false); // Internal calls usually per minute
                callRecord.setBilledAmount(priceWithVat.multiply(BigDecimal.valueOf(billedUnits)).setScale(2, RoundingMode.HALF_UP));

            } else {
                callRecord.setBilledAmount(BigDecimal.ZERO);
                callRecord.setPricePerMinute(BigDecimal.ZERO);
                callRecord.setInitialPrice(BigDecimal.ZERO);
            }
            log.debug("Call identified as Internal (Type: {}). CDR Hash: {}", internalCallResult.getTelephonyType().getName(), callRecord.getCdrHash());
            return;
        }
        
        handleExternalCallPricing(callRecord, rawData, effectiveDialedNumber, commLocation, originCountryId);
    }
    
    private boolean handleSpecialServices(CallRecord callRecord, String dialedNumber, CommunicationLocation commLocation, Long originCountryId) {
        List<String> pbxPrefixes = cdrConfigService.getPbxOutputPrefixes(commLocation);
        String numberForSpecialLookup = CdrHelper.cleanAndStripPhoneNumber(dialedNumber, pbxPrefixes, true);

        if (numberForSpecialLookup.isEmpty()) {
            return false;
        }

        Optional<SpecialService> specialServiceOpt = lookupService.findSpecialService(
            numberForSpecialLookup, 
            commLocation.getIndicatorId(),
            originCountryId
        );

        if (specialServiceOpt.isPresent()) {
            SpecialService ss = specialServiceOpt.get();
            setTelephonyTypeAndOperator(callRecord, TelephonyTypeConstants.NUMEROS_ESPECIALES, originCountryId, ss.getIndicator(), true);
            
            BigDecimal valueExVat = ss.getValue() != null ? ss.getValue() : BigDecimal.ZERO;
            // SpecialService.vatAmount is likely the percentage, not the calculated amount.
            BigDecimal vatRatePercentage = ss.getVatAmount() != null ? ss.getVatAmount() : BigDecimal.ZERO; 
            
            if (ss.getVatIncluded() != null && ss.getVatIncluded() && vatRatePercentage.compareTo(BigDecimal.ZERO) > 0) {
                 valueExVat = valueExVat.divide(BigDecimal.ONE.add(vatRatePercentage.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP)), 4, RoundingMode.HALF_UP);
            }
            callRecord.setInitialPrice(valueExVat.setScale(4, RoundingMode.HALF_UP));

            BigDecimal priceWithVat = valueExVat;
            if (vatRatePercentage.compareTo(BigDecimal.ZERO) > 0) {
                priceWithVat = valueExVat.multiply(BigDecimal.ONE.add(vatRatePercentage.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP))).setScale(4, RoundingMode.HALF_UP);
            }
            callRecord.setPricePerMinute(priceWithVat.setScale(4, RoundingMode.HALF_UP));
            
            int durationInSeconds = callRecord.getDuration() != null ? callRecord.getDuration() : 0;
            // Special services are often flat-rate or per-call, not per-minute. PHP's Calcular_Valor for special services
            // implies per-minute if duration_minuto > 0. Assuming per-minute for now.
            long billedUnits = CdrHelper.duracionMinuto(durationInSeconds, false); 
            callRecord.setBilledAmount(priceWithVat.multiply(BigDecimal.valueOf(billedUnits)).setScale(2, RoundingMode.HALF_UP));

            log.debug("Call identified as Special Service: {}. CDR Hash: {}", ss.getDescription(), callRecord.getCdrHash());
            return true;
        }
        return false;
    }

    private InternalCallTypeResultDto evaluateInternalCallType(
        String originExtensionStr, String destinationExtensionStr,
        CommunicationLocation currentCommLocation, InternalExtensionLimitsDto limits,
        Long currentOriginCountryId, LocalDateTime callDateTime,
        String originPartition, String destinationPartition) {

        // An internal call requires both parties to be identifiable as internal extensions.
        // PHP's `es_llamada_interna` checks if partitions are present and numbers look like extensions.
        boolean originIsPotentiallyInternal = (originPartition != null && !originPartition.isEmpty()) && CdrHelper.isPotentialExtension(originExtensionStr, limits);
        boolean destIsPotentiallyInternal = (destinationPartition != null && !destinationPartition.isEmpty()) && CdrHelper.isPotentialExtension(destinationExtensionStr, limits);

        if (!originIsPotentiallyInternal || !destIsPotentiallyInternal) {
            return null; // Not an internal-to-internal call based on partition/extension format
        }
        
        Optional<Employee> originEmpOpt = lookupService.findEmployeeByExtension(originExtensionStr, currentCommLocation.getId(), limits);
        Optional<Employee> destEmpOpt = lookupService.findEmployeeByExtension(destinationExtensionStr, currentCommLocation.getId(), limits);

        // If either employee is not found (even as a virtual range employee), it might not be a simple internal call,
        // or it might be an internal call to an unassigned extension.
        if (originEmpOpt.isEmpty() || destEmpOpt.isEmpty()) {
            // PHP has fallback: if destEmp not found, try matching destExtensionStr against internal prefixes (e.g. "07" for internal national)
            // or use CAPTURAS_INTERNADEF.
            if (destEmpOpt.isEmpty()) {
                List<Prefix> internalPrefixes = lookupService.findInternalPrefixesForType(TelephonyTypeConstants.INTERNA_NACIONAL, currentOriginCountryId); // Example type
                for (Prefix p : internalPrefixes) {
                    if (destinationExtensionStr.startsWith(p.getCode())) {
                        TelephonyType tt = p.getTelephonyType();
                        Operator op = p.getOperator();
                        // Destination indicator and employee would be unknown here.
                        log.debug("Internal call to {} matched internal prefix {}. Type: {}", destinationExtensionStr, p.getCode(), tt.getName());
                        return InternalCallTypeResultDto.builder()
                            .telephonyType(tt).operator(op).originEmployee(originEmpOpt.orElse(null))
                            .ignoreCall(false).isIncomingInternal(false) // Assuming outgoing if dest is by prefix
                            .build();
                    }
                }
                Long defaultInternalType = cdrConfigService.getDefaultInternalTelephonyTypeId();
                if (defaultInternalType != null) {
                    TelephonyType tt = lookupService.findTelephonyTypeById(defaultInternalType).orElse(null);
                    Operator op = lookupService.findInternalOperatorByTelephonyType(defaultInternalType, currentOriginCountryId).orElse(null);
                    log.debug("Internal call to {} using default internal type {}.", destinationExtensionStr, defaultInternalType);
                    return InternalCallTypeResultDto.builder()
                        .telephonyType(tt).operator(op).originEmployee(originEmpOpt.orElse(null))
                        .ignoreCall(false).isIncomingInternal(false)
                        .build();
                }
            }
            return null; // Could not resolve as internal
        }

        Employee originEmp = originEmpOpt.get();
        Employee destEmp = destEmpOpt.get();

        CommunicationLocation originCommLoc = originEmp.getCommunicationLocation() != null ? originEmp.getCommunicationLocation() :
                                              (originEmp.getCommunicationLocationId() != null ? lookupService.findCommunicationLocationById(originEmp.getCommunicationLocationId()).orElse(null) : null);
        CommunicationLocation destCommLoc = destEmp.getCommunicationLocation() != null ? destEmp.getCommunicationLocation() :
                                            (destEmp.getCommunicationLocationId() != null ? lookupService.findCommunicationLocationById(destEmp.getCommunicationLocationId()).orElse(null) : null);
        
        Indicator originIndicator = originCommLoc != null ? lookupService.findIndicatorForCommunicationLocation(originCommLoc).orElse(null) : null;
        Indicator destIndicator = destCommLoc != null ? lookupService.findIndicatorForCommunicationLocation(destCommLoc).orElse(null) : null;

        Long originEmpCountryId = originIndicator != null ? originIndicator.getOriginCountryId() : null;
        Long destEmpCountryId = destIndicator != null ? destIndicator.getOriginCountryId() : null;

        Subdivision originOffice = lookupService.findSubdivisionForEmployee(originEmp).map(this::getRootSubdivision).orElse(null);
        Subdivision destOffice = lookupService.findSubdivisionForEmployee(destEmp).map(this::getRootSubdivision).orElse(null);
        
        boolean ignoreCall = false;
        if (cdrConfigService.isGlobalExtensionsEnabled(currentCommLocation.getPlantType().getId()) &&
            originCommLoc != null && destCommLoc != null &&
            (!Objects.equals(currentCommLocation.getId(), originCommLoc.getId()) || !Objects.equals(currentCommLocation.getId(), destCommLoc.getId()))) {
            if (!Objects.equals(currentCommLocation.getId(), originCommLoc.getId()) && Objects.equals(currentCommLocation.getId(), destCommLoc.getId())) {
                ignoreCall = true; // Call originated elsewhere but terminated here (treat as incoming if not ignored)
            } else if (!Objects.equals(currentCommLocation.getId(), originCommLoc.getId()) && !Objects.equals(currentCommLocation.getId(), destCommLoc.getId())) {
                ignoreCall = true; // Call is entirely between other locations
            }
        }
        if (ignoreCall) {
            return InternalCallTypeResultDto.builder().ignoreCall(true).build();
        }

        Long telephonyTypeIdToUse;
        boolean isIncomingInternal = false;

        if (!Objects.equals(originEmpCountryId, destEmpCountryId)) {
            telephonyTypeIdToUse = TelephonyTypeConstants.INTERNA_INTERNACIONAL;
        } else if (originIndicator != null && destIndicator != null && !Objects.equals(originIndicator.getId(), destIndicator.getId())) {
            telephonyTypeIdToUse = TelephonyTypeConstants.INTERNA_NACIONAL;
        } else if (originOffice != null && destOffice != null && !Objects.equals(originOffice.getId(), destOffice.getId())) {
            telephonyTypeIdToUse = TelephonyTypeConstants.INTERNA_LOCAL;
        } else {
            telephonyTypeIdToUse = TelephonyTypeConstants.INTERNA;
        }
        
        if (originCommLoc == null || !Objects.equals(originCommLoc.getId(), currentCommLocation.getId())) {
            if (destCommLoc != null && Objects.equals(destCommLoc.getId(), currentCommLocation.getId())) {
                isIncomingInternal = true; // Originated at another internal location, terminated at current.
            }
        }

        TelephonyType tt = lookupService.findTelephonyTypeById(telephonyTypeIdToUse).orElse(null);
        Operator op = lookupService.findInternalOperatorByTelephonyType(telephonyTypeIdToUse, currentOriginCountryId).orElse(null);
        
        return InternalCallTypeResultDto.builder()
            .telephonyType(tt)
            .operator(op)
            .destinationIndicator(destIndicator)
            .originEmployee(originEmp)
            .destinationEmployee(destEmp)
            .ignoreCall(false)
            .isIncomingInternal(isIncomingInternal)
            .build();
    }

    private Subdivision getRootSubdivision(Subdivision subdivision) {
        if (subdivision == null) return null;
        Subdivision current = subdivision;
        // Assuming max depth to prevent infinite loops if data is cyclic (should not happen with proper FKs)
        for (int i=0; i < 10 && current.getParentSubdivisionId() != null; i++) { 
            Optional<Subdivision> parent = lookupService.findSubdivisionById(current.getParentSubdivisionId());
            if (parent.isPresent()) {
                current = parent.get();
            } else {
                break;
            }
        }
        return current;
    }


    private void handleExternalCallPricing(CallRecord callRecord, RawCdrData rawData, String dialedNumber, CommunicationLocation commLocation, Long originCountryId) {
        List<String> pbxPrefixes = cdrConfigService.getPbxOutputPrefixes(commLocation);
        boolean isTrunkCall = callRecord.getTrunk() != null && !callRecord.getTrunk().isEmpty();
        boolean isIncomingCall = callRecord.isIncoming();
        
        String numberForPrefixLookup = dialedNumber;
        if (!isTrunkCall && !isIncomingCall) { // For outgoing non-trunk, strip PBX prefix
            numberForPrefixLookup = CdrHelper.cleanAndStripPhoneNumber(dialedNumber, pbxPrefixes, false); // Not safe mode, if prefix defined but not found, it's empty
            if (numberForPrefixLookup.isEmpty() && !dialedNumber.isEmpty()) { // Prefix was expected but not found
                 log.warn("Outgoing non-trunk call {} for commLocation {} was expected to have a PBX prefix but did not. Treating as error.", dialedNumber, commLocation.getId());
                 setDefaultErrorTelephonyType(callRecord);
                 return;
            }
        } else if (isIncomingCall) {
            // For incoming, numberForPrefixLookup is the source. PHP's buscarOrigen strips PBX prefixes like "9"
            // This is tricky because "9" could be part of a real number.
            // For now, let's assume incoming numbers are "clean" or PBX rules handled it.
            // The `evaluateDestinationWithPrefix` `isIncomingMode` will handle not stripping operator prefixes.
        }
        // If isTrunkCall, numberForPrefixLookup remains original dialedNumber for now.
        // `evaluateDestinationWithPrefix` will handle trunk-specific prefix stripping rules.

        List<Prefix> prefixes = lookupService.findMatchingPrefixes(numberForPrefixLookup, originCountryId, isTrunkCall || isIncomingCall);
        EvaluatedDestinationDto bestEval = null;

        for (Prefix prefix : prefixes) {
            EvaluatedDestinationDto currentEval = evaluateDestinationWithPrefix(
                numberForPrefixLookup, // Number used for prefix matching
                dialedNumber,          // Original dialed number for context
                prefix, commLocation, originCountryId, isTrunkCall, isIncomingCall, pbxPrefixes, callRecord.getServiceDate()
            );
            if (currentEval != null && (bestEval == null || isBetterEvaluation(currentEval, bestEval, isIncomingCall))) {
                bestEval = currentEval;
                if (bestEval.getTelephonyType() != null &&
                    !Objects.equals(bestEval.getTelephonyType().getId(), TelephonyTypeConstants.ERRORES) &&
                    !bestEval.isAssumed()) {
                    break; 
                }
            }
        }
        
        // PHP: Normalization logic for trunk calls if initial eval is poor
        if (isTrunkCall && (bestEval == null || bestEval.getTelephonyType() == null || Objects.equals(bestEval.getTelephonyType().getId(), TelephonyTypeConstants.ERRORES) || bestEval.isAssumed())) {
            log.debug("Trunk call pricing was not definitive for {}. Attempting non-trunk 'normalization'. CDR Hash: {}", dialedNumber, callRecord.getCdrHash());
            String normalizedNumberLookup = CdrHelper.cleanAndStripPhoneNumber(dialedNumber, pbxPrefixes, true); 
            List<Prefix> normalizedPrefixes = lookupService.findMatchingPrefixes(normalizedNumberLookup, originCountryId, false); // false for non-trunk context

            if (!normalizedPrefixes.isEmpty()) {
                EvaluatedDestinationDto normalizedBestEval = null;
                for (Prefix normPrefix : normalizedPrefixes) {
                     EvaluatedDestinationDto currentNormEval = evaluateDestinationWithPrefix(
                        normalizedNumberLookup, 
                        dialedNumber,           
                        normPrefix, commLocation, originCountryId, false, false, pbxPrefixes, callRecord.getServiceDate() // isTrunk=false, isIncoming=false for normalization
                    );
                    if (currentNormEval != null && (normalizedBestEval == null || isBetterEvaluation(currentNormEval, normalizedBestEval, false))) {
                        normalizedBestEval = currentNormEval;
                         if (normalizedBestEval.getTelephonyType() != null &&
                            !Objects.equals(normalizedBestEval.getTelephonyType().getId(), TelephonyTypeConstants.ERRORES) &&
                            !normalizedBestEval.isAssumed()) {
                            break; 
                        }
                    }
                }
                if (normalizedBestEval != null && (bestEval == null || isBetterEvaluation(normalizedBestEval, bestEval, false))) {
                    log.info("Normalized pricing provided a better result for {}. Original trunk eval: {}. CDR Hash: {}", dialedNumber, bestEval, callRecord.getCdrHash());
                    bestEval = normalizedBestEval;
                    bestEval.setFromTrunk(true); // Still conceptually a trunk call
                    bestEval.setDestinationDescription(bestEval.getDestinationDescription() + " (Normalized)");
                }
            }
        }

        if (bestEval == null) {
            log.warn("No viable prefix/indicator combination found for {}. Falling back to error. CDR Hash: {}", dialedNumber, callRecord.getCdrHash());
            setDefaultErrorTelephonyType(callRecord);
            return;
        }

        applyEvaluationToCallRecord(callRecord, bestEval, commLocation, originCountryId);
    }
    
    private boolean isBetterEvaluation(EvaluatedDestinationDto newEval, EvaluatedDestinationDto oldEval, boolean isIncoming) {
        if (oldEval == null) return true;
        if (newEval.getTelephonyType() == null) return false; // New eval is invalid
        if (oldEval.getTelephonyType() == null) return true;  // Old eval was invalid, new one is better if it has a type

        boolean newIsError = Objects.equals(newEval.getTelephonyType().getId(), TelephonyTypeConstants.ERRORES);
        boolean oldIsError = Objects.equals(oldEval.getTelephonyType().getId(), TelephonyTypeConstants.ERRORES);

        if (oldIsError && !newIsError) return true; // New is not error, old was error
        if (!oldIsError && newIsError) return false; // New is error, old was not
        if (oldIsError && newIsError) return false; // Both are errors, keep old

        // Prefer non-assumed results
        if (oldEval.isAssumed() && !newEval.isAssumed()) return true;
        if (!oldEval.isAssumed() && newEval.isAssumed()) return false;
        
        // For incoming calls, a more specific (non-Local) type might be preferred over an assumed Local
        if (isIncoming) {
            boolean oldIsLocal = Objects.equals(oldEval.getTelephonyType().getId(), TelephonyTypeConstants.LOCAL);
            boolean newIsLocal = Objects.equals(newEval.getTelephonyType().getId(), TelephonyTypeConstants.LOCAL);
            if (oldIsLocal && !newIsLocal && oldEval.isAssumed() && !newEval.isAssumed()) return true; // Prefer specific non-local over assumed local
            if (oldIsLocal && !newIsLocal && oldEval.isAssumed()) return true; // Prefer any non-local over assumed local
            if (oldIsLocal && !newIsLocal && !oldEval.isAssumed() && !newEval.isAssumed()) return false; // Prefer specific local over specific non-local if both are specific (unlikely scenario for incoming)
        }
        
        // If both are similar in error/assumed status, could add more criteria (e.g. more specific indicator match)
        // For now, default to keeping the old one if no clear improvement
        return false;
    }


    private EvaluatedDestinationDto evaluateDestinationWithPrefix(
        String numberUsedForPrefixMatch, String originalDialedNumberForContext, Prefix prefix,
        CommunicationLocation commLocation, Long originCountryId, boolean isTrunkCallContext, boolean isIncomingCall,
        List<String> pbxPrefixes, LocalDateTime callDateTime) {

        BigDecimal currentCallValueExVat;
        boolean currentVatIncludedInSource = prefix.isVatIncluded();
        BigDecimal currentVatRate = prefix.getVatValue() != null ? prefix.getVatValue() : BigDecimal.ZERO;
        
        currentCallValueExVat = prefix.getBaseValue() != null ? prefix.getBaseValue() : BigDecimal.ZERO;
        if (currentVatIncludedInSource && currentVatRate.compareTo(BigDecimal.ZERO) > 0) {
            currentCallValueExVat = currentCallValueExVat.divide(BigDecimal.ONE.add(currentVatRate.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP)), 4, RoundingMode.HALF_UP);
        }

        Long currentTelephonyTypeId = prefix.getTelephoneTypeId();
        TelephonyType currentTelephonyType = prefix.getTelephonyType();
        Operator currentOperator = prefix.getOperator();
        Long currentOperatorId = prefix.getOperatorId();
        String currentDestinationDescription = currentTelephonyType != null ? currentTelephonyType.getName() : "Unknown";
        Indicator currentIndicator = null;
        Long bandIdForSpecialRate = null;
        String bandName = null;
        boolean currentBilledInSeconds = false; 
        Integer currentBillingUnit = 60; 

        String numberAfterPrefix = numberUsedForPrefixMatch; // Start with the number used for prefix matching
        if (!isIncomingCall && prefix.getCode() != null && !prefix.getCode().isEmpty() && numberUsedForPrefixMatch.startsWith(prefix.getCode())) {
            // For outgoing, strip the operator prefix
            numberAfterPrefix = numberUsedForPrefixMatch.substring(prefix.getCode().length());
        }
        // For incoming, numberAfterPrefix is the full source number, prefix.getCode() is used to identify its type.

        String numberForIndicatorLookup = numberAfterPrefix;
        Long typeForIndicatorLookup = currentTelephonyTypeId;
        TelephonyTypeConfig ttConfig = lookupService.findTelephonyTypeConfigForLengthDetermination(currentTelephonyTypeId, originCountryId).orElse(null);
        int minLengthForType = (ttConfig != null) ? ttConfig.getMinValue() : 0;
        
        if (Objects.equals(currentTelephonyTypeId, TelephonyTypeConstants.LOCAL) && !isIncomingCall) { // Local outgoing
            typeForIndicatorLookup = TelephonyTypeConstants.NACIONAL;
            String localAreaCode = lookupService.findLocalAreaCodeForIndicator(commLocation.getIndicatorId());
            numberForIndicatorLookup = localAreaCode + numberAfterPrefix;
            TelephonyTypeConfig nationalTtConfig = lookupService.findTelephonyTypeConfigForLengthDetermination(TelephonyTypeConstants.NACIONAL, originCountryId).orElse(null);
            minLengthForType = (nationalTtConfig != null) ? nationalTtConfig.getMinValue() : 0;
        }
        
        int maxLengthForType = (ttConfig != null) ? ttConfig.getMaxValue() : Integer.MAX_VALUE;
        if (numberForIndicatorLookup.length() > maxLengthForType && maxLengthForType > 0) {
            numberForIndicatorLookup = numberForIndicatorLookup.substring(0, maxLengthForType);
        }

        List<SeriesMatchDto> seriesMatches = Collections.emptyList();
        boolean assumed = false;

        if (numberForIndicatorLookup.length() >= minLengthForType || isIncomingCall) { // For incoming, always try to match
             seriesMatches = lookupService.findIndicatorsByNumberAndType(
                numberForIndicatorLookup, typeForIndicatorLookup, originCountryId, commLocation, prefix.getId(), prefix.isBandOk(), isIncomingCall
            );
        }
        
        if (!seriesMatches.isEmpty()) {
            SeriesMatchDto bestMatch = seriesMatches.get(0);
            currentIndicator = bestMatch.getIndicator();
            currentDestinationDescription = bestMatch.getDestinationDescription();
            assumed = bestMatch.isApproximate();

            if (Objects.equals(currentTelephonyTypeId, TelephonyTypeConstants.LOCAL) && !isIncomingCall &&
                commLocation.getIndicator() != null && currentIndicator != null &&
                !Objects.equals(commLocation.getIndicatorId(), currentIndicator.getId()) &&
                lookupService.isLocalExtended(commLocation.getIndicator(), currentIndicator)) {
                
                currentTelephonyTypeId = TelephonyTypeConstants.LOCAL_EXTENDIDA;
                currentTelephonyType = lookupService.findTelephonyTypeById(TelephonyTypeConstants.LOCAL_EXTENDIDA).orElse(currentTelephonyType);
                currentDestinationDescription = (currentTelephonyType != null ? currentTelephonyType.getName() : "Local Ext.") + " (" + bestMatch.getDestinationDescription() + ")";
            }
        } else if (Objects.equals(currentTelephonyTypeId, TelephonyTypeConstants.LOCAL) && !isIncomingCall) {
            currentIndicator = commLocation.getIndicator();
            if (currentIndicator != null) {
                 currentDestinationDescription = buildDestinationDescription(currentIndicator, commLocation.getIndicator()) + " (Assumed)";
            } else {
                currentDestinationDescription = "Local (Assumed, No Comm Indicator)";
            }
            assumed = true;
        } else if (isIncomingCall) { // For incoming, if no series match, it's an unknown origin
            currentDestinationDescription = "Unknown Origin (" + currentTelephonyType.getName() + ")";
            assumed = true;
        } else {
            log.debug("No indicator for prefix {}, number part {}, type {}. This path is not viable.", prefix.getCode(), numberAfterPrefix, currentTelephonyTypeId);
             return EvaluatedDestinationDto.builder()
                .processedNumber(numberAfterPrefix)
                .telephonyType(lookupService.findTelephonyTypeById(TelephonyTypeConstants.ERRORES).orElse(null))
                .destinationDescription("No Indicator Match")
                .pricePerUnitExVat(BigDecimal.ZERO)
                .vatIncludedInPrefixOrBand(false).vatRate(BigDecimal.ZERO)
                .billedInSeconds(false).billingUnitInSeconds(60)
                .assumed(true) 
                .build();
        }

        BigDecimal initialPriceExVat = currentCallValueExVat; 

        if (prefix.isBandOk() && currentIndicator != null && !isIncomingCall) { // Bands usually for outgoing
            List<Band> bands = lookupService.findBandsForPrefixAndIndicator(prefix.getId(), commLocation.getIndicatorId());
            if (!bands.isEmpty()) {
                Band matchedBand = bands.get(0); // PHP takes first from ordered list
                currentCallValueExVat = matchedBand.getValue() != null ? matchedBand.getValue() : BigDecimal.ZERO;
                currentVatIncludedInSource = matchedBand.getVatIncluded(); 
                bandIdForSpecialRate = matchedBand.getId();
                bandName = matchedBand.getName();
                
                // VAT rate for band context is still from the prefix
                if (currentVatIncludedInSource && currentVatRate.compareTo(BigDecimal.ZERO) > 0) { 
                    currentCallValueExVat = currentCallValueExVat.divide(BigDecimal.ONE.add(currentVatRate.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP)), 4, RoundingMode.HALF_UP);
                }
                initialPriceExVat = currentCallValueExVat; 
            }
        }
        
        SpecialRateApplicationResultDto srResult = applySpecialRates(
            currentCallValueExVat, // This is already ExVAT
            callDateTime, currentTelephonyTypeId, currentOperatorId, bandIdForSpecialRate, commLocation.getIndicatorId()
        );
        if (srResult.isRateWasApplied()) {
            currentCallValueExVat = srResult.getNewPricePerUnitExVat(); 
            currentVatRate = srResult.getNewVatRate(); 
        }

        return EvaluatedDestinationDto.builder()
            .processedNumber(numberAfterPrefix)
            .telephonyType(currentTelephonyType)
            .operator(currentOperator)
            .indicator(currentIndicator)
            .destinationDescription(currentDestinationDescription)
            .pricePerUnitExVat(currentCallValueExVat) 
            .vatIncludedInPrefixOrBand(currentVatIncludedInSource) 
            .vatRate(currentVatRate)
            .billedInSeconds(currentBilledInSeconds)
            .billingUnitInSeconds(currentBillingUnit)
            .fromTrunk(isTrunkCallContext)
            .bandUsed(bandIdForSpecialRate != null)
            .bandId(bandIdForSpecialRate)
            .bandName(bandName)
            .assumed(assumed)
            .build();
    }
    
    private SpecialRateApplicationResultDto applySpecialRates(
        BigDecimal priceBeforeSpecialRateExVat,
        LocalDateTime callTime, Long telephonyTypeId, Long operatorId, Long bandId, Long originIndicatorId) {

        List<SpecialRateValue> specialRates = lookupService.findSpecialRateValues(
            callTime, telephonyTypeId, operatorId, bandId, originIndicatorId
        );

        if (!specialRates.isEmpty()) {
            for (SpecialRateValue sr : specialRates) { // PHP uses first match from ordered list
                if (isTimeInHoursSpecification(callTime, sr.getHoursSpecification())) {
                    BigDecimal newPriceExVat;
                    // VAT rate for special rate context should be determined by SR's TT/Op or fallback
                    BigDecimal srEffectiveVatRate = lookupService.findVatForTelephonyOperator(
                                                        sr.getTelephonyTypeId() != null ? sr.getTelephonyTypeId() : telephonyTypeId,
                                                        sr.getOperatorId() != null ? sr.getOperatorId() : operatorId,
                                                        sr.getOriginIndicatorId() != null ? sr.getOriginIndicatorId() : originIndicatorId) // Use SR's origin if specified
                                                        .orElse(BigDecimal.ZERO);

                    if (sr.getValueType() != null && sr.getValueType() == 1) { // Percentage discount
                        BigDecimal discountPercentage = sr.getRateValue().divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
                        newPriceExVat = priceBeforeSpecialRateExVat.multiply(BigDecimal.ONE.subtract(discountPercentage));
                    } else { // Fixed value
                        newPriceExVat = sr.getRateValue();
                        if (sr.getIncludesVat() && srEffectiveVatRate.compareTo(BigDecimal.ZERO) > 0) {
                            newPriceExVat = newPriceExVat.divide(
                                BigDecimal.ONE.add(srEffectiveVatRate.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP)),
                                10, RoundingMode.HALF_UP);
                        }
                    }
                    return SpecialRateApplicationResultDto.builder()
                        .newPricePerUnitExVat(newPriceExVat) 
                        .newVatRate(srEffectiveVatRate)
                        .appliedRule(sr)
                        .rateWasApplied(true)
                        .build();
                }
            }
        }
        // If no special rate applied, return original price context (VAT rate from original prefix/band)
        BigDecimal originalVatRate = lookupService.findVatForTelephonyOperator(telephonyTypeId, operatorId, originIndicatorId).orElse(BigDecimal.ZERO);
        return SpecialRateApplicationResultDto.builder().rateWasApplied(false)
            .newPricePerUnitExVat(priceBeforeSpecialRateExVat).newVatRate(originalVatRate).build();
    }

    private boolean isTimeInHoursSpecification(LocalDateTime callTime, String hoursSpecification) {
        if (hoursSpecification == null || hoursSpecification.trim().isEmpty()) {
            return true; // No specific hours means applies all day
        }
        int callHour = callTime.getHour();
        String[] parts = hoursSpecification.split(",");
        for (String part : parts) {
            part = part.trim();
            if (part.contains("-")) {
                String[] range = part.split("-");
                if (range.length == 2) {
                    try {
                        int startHour = Integer.parseInt(range[0].trim());
                        int endHour = Integer.parseInt(range[1].trim());
                        if (callHour >= startHour && callHour <= endHour) {
                            return true;
                        }
                    } catch (NumberFormatException e) {
                        log.warn("Invalid hour range in specification: {}", part);
                    }
                }
            } else {
                try {
                    if (callHour == Integer.parseInt(part)) {
                        return true;
                    }
                } catch (NumberFormatException e) {
                    log.warn("Invalid hour in specification: {}", part);
                }
            }
        }
        return false;
    }

    private TrunkRuleApplicationResultDto applyTrunkRules(
        CallRecord callRecord, 
        BigDecimal currentPriceExVat, BigDecimal currentVatRate, Integer currentBillingUnit,
        TelephonyType currentTelephonyType, Operator currentOperator, Indicator currentIndicator,
        CommunicationLocation commLocation, Long originCountryId) {
        
        if (callRecord.getTrunk() == null || callRecord.getTrunk().isEmpty() || currentIndicator == null || currentTelephonyType == null) {
            return TrunkRuleApplicationResultDto.builder().ruleWasApplied(false)
                .newPricePerUnitExVat(currentPriceExVat).newVatRate(currentVatRate).newBillingUnitInSeconds(currentBillingUnit).build();
        }

        Optional<Trunk> trunkOpt = lookupService.findTrunkByNameAndCommLocation(callRecord.getTrunk(), commLocation.getId());
        if (trunkOpt.isEmpty()) {
             return TrunkRuleApplicationResultDto.builder().ruleWasApplied(false)
                .newPricePerUnitExVat(currentPriceExVat).newVatRate(currentVatRate).newBillingUnitInSeconds(currentBillingUnit).build();
        }

        List<TrunkRule> trunkRules = lookupService.findTrunkRules(
            trunkOpt.get().getId(),
            currentTelephonyType.getId(),
            String.valueOf(currentIndicator.getId()),
            commLocation.getIndicatorId()
        );

        if (!trunkRules.isEmpty()) {
            TrunkRule rule = trunkRules.get(0); // PHP uses first match from ordered list
            log.debug("Applying trunk rule ID: {}. CDR Hash: {}", rule.getId(), callRecord.getCdrHash());

            BigDecimal ruleRateValue = rule.getRateValue();
            boolean ruleRateIsVatIncluded = rule.getIncludesVat() != null && rule.getIncludesVat();
            
            TelephonyType ruleTelephonyType = rule.getNewTelephonyType() != null ? rule.getNewTelephonyType() : currentTelephonyType;
            Operator ruleOperator = rule.getNewOperator() != null ? rule.getNewOperator() : currentOperator;
            Indicator ruleOriginIndicator = rule.getOriginIndicator() != null ? rule.getOriginIndicator() : commLocation.getIndicator(); 
            
            BigDecimal ruleEffectiveVatRate = lookupService.findVatForTelephonyOperator(
                                                ruleTelephonyType.getId(), 
                                                ruleOperator != null ? ruleOperator.getId() : null, 
                                                ruleOriginIndicator != null ? ruleOriginIndicator.getOriginCountryId() : originCountryId)
                                                .orElse(BigDecimal.ZERO);
            
            BigDecimal priceAfterRuleExVat = ruleRateValue;
            if (ruleRateIsVatIncluded && ruleEffectiveVatRate.compareTo(BigDecimal.ZERO) > 0) {
                priceAfterRuleExVat = ruleRateValue.divide(
                    BigDecimal.ONE.add(ruleEffectiveVatRate.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP)),
                    10, RoundingMode.HALF_UP);
            }

            Integer billingUnit = rule.getSeconds() != null && rule.getSeconds() > 0 ? rule.getSeconds() : currentBillingUnit;

            return TrunkRuleApplicationResultDto.builder()
                .newPricePerUnitExVat(priceAfterRuleExVat) 
                .newVatRate(ruleEffectiveVatRate)
                .newBillingUnitInSeconds(billingUnit)
                .newTelephonyType(ruleTelephonyType)
                .newOperator(ruleOperator)
                .newOriginIndicator(ruleOriginIndicator)
                .appliedRule(rule)
                .ruleWasApplied(true)
                .build();
        }
        return TrunkRuleApplicationResultDto.builder().ruleWasApplied(false)
            .newPricePerUnitExVat(currentPriceExVat).newVatRate(currentVatRate).newBillingUnitInSeconds(currentBillingUnit).build();
    }

    private void applyEvaluationToCallRecord(CallRecord callRecord, EvaluatedDestinationDto evalDto, CommunicationLocation commLocation, Long originCountryId) {
        BigDecimal initialPriceExVat = evalDto.getPricePerUnitExVat(); // Price from prefix/band, ex-VAT
        BigDecimal currentPricePerUnitExVat = evalDto.getPricePerUnitExVat();
        BigDecimal currentVatRate = evalDto.getVatRate();
        Integer currentBillingUnit = evalDto.getBillingUnitInSeconds();
        TelephonyType currentTelephonyType = evalDto.getTelephonyType();
        Operator currentOperator = evalDto.getOperator();
        Indicator currentIndicator = evalDto.getIndicator();

        if (evalDto.isFromTrunk()) {
            Optional<TrunkRate> trunkRateOpt = lookupService.findTrunkRate(
                lookupService.findTrunkByNameAndCommLocation(callRecord.getTrunk(), commLocation.getId()).map(Trunk::getId).orElse(null),
                currentOperator != null ? currentOperator.getId() : null,
                currentTelephonyType != null ? currentTelephonyType.getId() : null
            );
            if (trunkRateOpt.isPresent()) {
                TrunkRate tr = trunkRateOpt.get();
                currentPricePerUnitExVat = tr.getRateValue();
                if (tr.getIncludesVat() && currentVatRate.compareTo(BigDecimal.ZERO) > 0) { // Assuming currentVatRate is still relevant
                    currentPricePerUnitExVat = currentPricePerUnitExVat.divide(BigDecimal.ONE.add(currentVatRate.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP)), 4, RoundingMode.HALF_UP);
                }
                currentBillingUnit = tr.getSeconds() != null && tr.getSeconds() > 0 ? tr.getSeconds() : 60;
                initialPriceExVat = currentPricePerUnitExVat; // TrunkRate overrides prefix/band initial price
                log.debug("Applied TrunkRate ID {} for trunk {}. New base price exVAT: {}, billing unit: {}. CDR Hash: {}",
                    tr.getId(), callRecord.getTrunk(), currentPricePerUnitExVat, currentBillingUnit, callRecord.getCdrHash());
            }
        }
        
        TrunkRuleApplicationResultDto trunkResult = applyTrunkRules(callRecord, 
            currentPricePerUnitExVat, currentVatRate, currentBillingUnit,
            currentTelephonyType, currentOperator, currentIndicator,
            commLocation, originCountryId);
        
        if (trunkResult.isRuleWasApplied()) {
            currentPricePerUnitExVat = trunkResult.getNewPricePerUnitExVat();
            currentVatRate = trunkResult.getNewVatRate();
            currentBillingUnit = trunkResult.getNewBillingUnitInSeconds();
            if (trunkResult.getNewTelephonyType() != null) currentTelephonyType = trunkResult.getNewTelephonyType();
            if (trunkResult.getNewOperator() != null) currentOperator = trunkResult.getNewOperator();
            if (trunkResult.getNewOriginIndicator() != null) { /* currentIndicator might change if rule changes origin context */ }
            initialPriceExVat = currentPricePerUnitExVat; // TrunkRule rate becomes the new base
        }

        SpecialRateApplicationResultDto srResult = applySpecialRates(
            currentPricePerUnitExVat, callRecord.getServiceDate(), 
            currentTelephonyType != null ? currentTelephonyType.getId() : null, 
            currentOperator != null ? currentOperator.getId() : null, 
            evalDto.getBandId(), commLocation.getIndicatorId()
        );
        if (srResult.isRateWasApplied()) {
            currentPricePerUnitExVat = srResult.getNewPricePerUnitExVat();
            currentVatRate = srResult.getNewVatRate();
        }

        callRecord.setInitialPrice(initialPriceExVat.setScale(4, RoundingMode.HALF_UP)); 

        BigDecimal finalPricePerUnitWithVat = currentPricePerUnitExVat;
        if (currentVatRate.compareTo(BigDecimal.ZERO) > 0) {
            finalPricePerUnitWithVat = currentPricePerUnitExVat.multiply(
                BigDecimal.ONE.add(currentVatRate.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP))
            );
        }
        callRecord.setPricePerMinute(finalPricePerUnitWithVat.setScale(4, RoundingMode.HALF_UP));

        int durationInSeconds = callRecord.getDuration() != null ? callRecord.getDuration() : 0;
        long billedUnits = (long) Math.ceil((double) durationInSeconds / (double) Math.max(1, currentBillingUnit)); // Avoid division by zero
        if (billedUnits == 0 && durationInSeconds > 0) billedUnits = 1; // Minimum 1 unit if any duration
        
        callRecord.setBilledAmount(finalPricePerUnitWithVat.multiply(BigDecimal.valueOf(billedUnits)).setScale(2, RoundingMode.HALF_UP));

        callRecord.setTelephonyType(currentTelephonyType);
        callRecord.setTelephonyTypeId(currentTelephonyType != null ? currentTelephonyType.getId() : TelephonyTypeConstants.ERRORES);
        callRecord.setOperator(currentOperator);
        callRecord.setOperatorId(currentOperator != null ? currentOperator.getId() : null);
        callRecord.setIndicator(currentIndicator);
        callRecord.setIndicatorId(currentIndicator != null ? currentIndicator.getId() : null);
    }


    private void setDefaultErrorTelephonyType(CallRecord callRecord) {
        callRecord.setTelephonyTypeId(TelephonyTypeConstants.ERRORES);
        lookupService.findTelephonyTypeById(TelephonyTypeConstants.ERRORES).ifPresent(callRecord::setTelephonyType);
        callRecord.setBilledAmount(BigDecimal.ZERO);
        callRecord.setPricePerMinute(BigDecimal.ZERO);
        callRecord.setInitialPrice(BigDecimal.ZERO);
    }

    private void setTelephonyTypeAndOperator(CallRecord callRecord, Long telephonyTypeId, Long originCountryId, Indicator indicator, boolean forceOperatorUpdate) {
        lookupService.findTelephonyTypeById(telephonyTypeId).ifPresent(tt -> {
            callRecord.setTelephonyType(tt);
            callRecord.setTelephonyTypeId(tt.getId());
        });
        if (callRecord.getOperatorId() == null || forceOperatorUpdate) { 
            lookupService.findInternalOperatorByTelephonyType(telephonyTypeId, originCountryId)
                .ifPresent(op -> {
                    callRecord.setOperator(op);
                    callRecord.setOperatorId(op.getId());
                });
        }
        if (indicator != null) {
            callRecord.setIndicator(indicator);
            callRecord.setIndicatorId(indicator.getId());
        }
    }
    
    private String applyPbxSpecialRules(String dialedNumber, CommunicationLocation commLocation, boolean isIncoming) {
        if (dialedNumber == null) return null;
        List<PbxSpecialRule> rules = lookupService.findPbxSpecialRules(commLocation.getId());
        String currentNumber = dialedNumber;

        for (PbxSpecialRule rule : rules) {
            PbxSpecialRuleDirection ruleDirection = PbxSpecialRuleDirection.fromValue(rule.getDirection());
            boolean directionMatch = (ruleDirection == PbxSpecialRuleDirection.BOTH) ||
                                     (isIncoming && ruleDirection == PbxSpecialRuleDirection.INCOMING) ||
                                     (!isIncoming && ruleDirection == PbxSpecialRuleDirection.OUTGOING);

            if (!directionMatch) continue;
            
            if (currentNumber.length() < rule.getMinLength()) continue;

            if (rule.getSearchPattern() != null && !rule.getSearchPattern().isEmpty() && currentNumber.startsWith(rule.getSearchPattern())) {
                boolean ignore = false;
                if (rule.getIgnorePattern() != null && !rule.getIgnorePattern().isEmpty()) {
                    String[] ignorePatternsText = rule.getIgnorePattern().split(",");
                    for (String ignorePatStr : ignorePatternsText) {
                        if (ignorePatStr.trim().isEmpty()) continue;
                        // PHP's strpos($dialedNumber, $ignorePatStr.trim()) !== false means "contains"
                        if (dialedNumber.contains(ignorePatStr.trim())) { 
                            ignore = true;
                            break;
                        }
                    }
                }
                if (ignore) continue;
                
                String replacement = rule.getReplacement() != null ? rule.getReplacement() : "";
                currentNumber = replacement + currentNumber.substring(rule.getSearchPattern().length());
                log.debug("Applied PBX rule ID {}: {} -> {}", rule.getId(), dialedNumber, currentNumber);
                // PHP applies first matching rule and returns.
                return currentNumber; 
            }
        }
        return currentNumber; // Return original or last modified if no rule applied this iteration
    }

    private String buildDestinationDescription(Indicator matchedIndicator, Indicator originCommIndicator) {
        if (matchedIndicator == null) return "Unknown";
        String city = matchedIndicator.getCityName();
        String deptCountry = matchedIndicator.getDepartmentCountry();
        if (city != null && !city.isEmpty()) {
            if (deptCountry != null && !deptCountry.isEmpty() && !city.equalsIgnoreCase(deptCountry)) {
                // Check if it's local extended only if origin and destination indicators are different
                if (originCommIndicator != null && !Objects.equals(originCommIndicator.getId(), matchedIndicator.getId()) &&
                    lookupService.isLocalExtended(originCommIndicator, matchedIndicator)) {
                    return city + " (" + deptCountry + " - Local Ext.)";
                }
                return city + " (" + deptCountry + ")";
            }
            return city;
        }
        return deptCountry != null ? deptCountry : "N/A";
    }
}