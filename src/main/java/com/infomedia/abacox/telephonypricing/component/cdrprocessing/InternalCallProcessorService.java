// File: com/infomedia/abacox/telephonypricing/component/cdrprocessing/InternalCallProcessorService.java
package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import com.infomedia.abacox.telephonypricing.db.entity.CommunicationLocation;
import com.infomedia.abacox.telephonypricing.db.entity.Employee;
import com.infomedia.abacox.telephonypricing.db.entity.Indicator;
import com.infomedia.abacox.telephonypricing.db.entity.Subdivision;
import com.infomedia.abacox.telephonypricing.multitenancy.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Log4j2
@RequiredArgsConstructor
public class InternalCallProcessorService {

    @PersistenceContext
    private EntityManager entityManager;

    private final EmployeeLookupService employeeLookupService;
    private final TariffCalculationService tariffCalculationService;
    private final CdrConfigService appConfigService;
    private final TelephonyTypeLookupService telephonyTypeLookupService;
    private final PrefixLookupService prefixLookupService;

    // PHP asignar_ubicacion (txt2dbv8.txt:1035) takes the call's origin/destination indicator from
    // the employee's SUBDIVISION -> OFFICE_DETAILS -> INDICATOR_ID, not from the commLocation.
    // The subdivision's office can live in a different indicator than the commLocation's (seen in
    // colsanitas: subdivision 2290 "CM EPS EL BARZAL" sits at indicator 156/Popayan even though
    // its employees belong to commLocation 50/Villavicencio/1045). Falling back to commLocation
    // when the subdivision has no office_details row matches legacy fallback behavior.
    // Cache key is "<tenant>:<subdivisionId>" so tenants cannot leak.
    // Sentinel -1L is used to cache "no office found" to avoid repeated empty queries.
    private static final Long OFFICE_INDICATOR_MISSING = -1L;
    private final Map<String, Long> officeIndicatorBySubdivision = new ConcurrentHashMap<>();

    @Transactional(readOnly = true)
    public Long resolveOfficeIndicatorId(Long subdivisionId) {
        if (subdivisionId == null || subdivisionId <= 0) return null;
        String key = TenantContext.getTenant() + ":" + subdivisionId;
        Long cached = officeIndicatorBySubdivision.computeIfAbsent(key, k -> {
            List<Number> rows = entityManager.createNativeQuery(
                    "SELECT indicator_id FROM office_details WHERE subdivision_id = :subId AND active = true LIMIT 1")
                    .setParameter("subId", subdivisionId)
                    .getResultList();
            if (rows.isEmpty()) return OFFICE_INDICATOR_MISSING;
            Number n = rows.get(0);
            return n == null ? OFFICE_INDICATOR_MISSING : n.longValue();
        });
        return OFFICE_INDICATOR_MISSING.equals(cached) ? null : cached;
    }

    private Long effectiveIndicatorId(Employee emp, CommunicationLocation fallback) {
        if (emp != null) {
            Long subId = emp.getSubdivisionId();
            if (subId != null) {
                Long officeInd = resolveOfficeIndicatorId(subId);
                if (officeInd != null) return officeInd;
            }
        }
        if (fallback != null && fallback.getIndicator() != null) {
            return fallback.getIndicator().getId();
        }
        return null;
    }

    public void processInternal(CdrData cdrData, LineProcessingContext processingContext,
                                boolean pbxSpecialRuleAppliedRecursively) {
        CommunicationLocation commLocation = processingContext.getCommLocation();
        log.debug("Processing INTERNAL call logic for CDR: {}. Recursive PBX applied: {}", cdrData.getCtlHash(),
                pbxSpecialRuleAppliedRecursively);

        List<String> prefixesToClean = Collections.emptyList();
        boolean stripOnlyIfPrefixMatchesAndFound = false;
        if (pbxSpecialRuleAppliedRecursively && commLocation.getPbxPrefix() != null
                && !commLocation.getPbxPrefix().isEmpty()) {
            prefixesToClean = Arrays.asList(commLocation.getPbxPrefix().split(","));
            stripOnlyIfPrefixMatchesAndFound = true;
        }

        String cleanedDestination = CdrUtil.cleanPhoneNumber(
                cdrData.getEffectiveDestinationNumber(),
                prefixesToClean,
                stripOnlyIfPrefixMatchesAndFound).getCleanedNumber();
        cdrData.setEffectiveDestinationNumber(cleanedDestination);
        log.debug("Cleaned internal destination: {}", cleanedDestination);

        if (cdrData.getCallingPartyNumber() != null && !cdrData.getCallingPartyNumber().trim().isEmpty() &&
                Objects.equals(cdrData.getCallingPartyNumber().trim(), cleanedDestination.trim())) {
            log.debug("Internal call to self (Origin: {}, Destination: {}). Marking for quarantine.",
                    cdrData.getCallingPartyNumber(), cleanedDestination);
            cdrData.setTelephonyTypeId(TelephonyTypeEnum.ERRORS.getValue());
            cdrData.setTelephonyTypeName("Internal Self-Call (Ignored)");
            cdrData.setMarkedForQuarantine(true);
            cdrData.setQuarantineReason("Internal call to self (PHP: IGUALDESTINO)");
            cdrData.setQuarantineStep(QuarantineErrorType.INTERNAL_SELF_CALL.name());
            return;
        }

        InternalCallTypeInfo internalTypeInfo = determineSpecificInternalCallType(cdrData, processingContext);
        log.debug("Determined specific internal call type info: {}", internalTypeInfo);

        if (internalTypeInfo.isIgnoreCall()) {
            log.debug("Internal call marked to be ignored. Reason: {}", internalTypeInfo.getAdditionalInfo());
            cdrData.setTelephonyTypeId(TelephonyTypeEnum.ERRORS.getValue());
            cdrData.setTelephonyTypeName(
                    "Internal Call Ignored (Policy: " + internalTypeInfo.getAdditionalInfo() + ")");
            cdrData.setMarkedForQuarantine(true);
            cdrData.setQuarantineReason(
                    internalTypeInfo.getAdditionalInfo() != null ? internalTypeInfo.getAdditionalInfo()
                            : "Internal call ignore policy");

            // Apply specific error type mapped in determination phase
            cdrData.setQuarantineStep(internalTypeInfo.getErrorType() != null
                    ? internalTypeInfo.getErrorType().name()
                    : QuarantineErrorType.INTERNAL_POLICY_IGNORE.name());
            return;
        }

        // PHP: procesaInterna -> InvertirLlamada if origin not found but destination is.
        if (internalTypeInfo.isEffectivelyIncoming() && cdrData.getCallDirection() == CallDirection.OUTGOING) {
            log.debug("Internal call determined to be effectively incoming. Inverting parties and trunks. CDR: {}",
                    cdrData.getCtlHash());
            CdrUtil.swapFull(cdrData, true); // Full swap including trunks
            cdrData.setCallDirection(CallDirection.INCOMING);

            cdrData.setEmployee(internalTypeInfo.getDestinationEmployee());
            cdrData.setEmployeeId(internalTypeInfo.getDestinationEmployee() != null
                    ? internalTypeInfo.getDestinationEmployee().getId() : null);

            cdrData.setDestinationEmployee(internalTypeInfo.getOriginEmployee());
            cdrData.setDestinationEmployeeId(
                    internalTypeInfo.getOriginEmployee() != null ? internalTypeInfo.getOriginEmployee().getId() : null);

            cdrData.setIndicatorId(internalTypeInfo.getDestinationIndicatorId());
        } else {
            // Standard assignment if not inverted
            cdrData.setEmployee(internalTypeInfo.getOriginEmployee());
            cdrData.setEmployeeId(
                    internalTypeInfo.getOriginEmployee() != null ? internalTypeInfo.getOriginEmployee().getId() : null);
            cdrData.setDestinationEmployee(internalTypeInfo.getDestinationEmployee());
            cdrData.setDestinationEmployeeId(internalTypeInfo.getDestinationEmployee() != null
                    ? internalTypeInfo.getDestinationEmployee().getId() : null);
            cdrData.setIndicatorId(internalTypeInfo.getDestinationIndicatorId());
        }

        cdrData.setTelephonyTypeId(internalTypeInfo.getTelephonyTypeId());
        cdrData.setTelephonyTypeName(internalTypeInfo.getTelephonyTypeName());
        if (internalTypeInfo.getAdditionalInfo() != null && !internalTypeInfo.getAdditionalInfo().isEmpty()) {
            cdrData.setTelephonyTypeName(cdrData.getTelephonyTypeName() + " " + internalTypeInfo.getAdditionalInfo());
        }

        if (cdrData.getTelephonyTypeId() != null && commLocation.getIndicator() != null) {
            OperatorInfo internalOp = telephonyTypeLookupService.getInternalOperatorInfo(
                    cdrData.getTelephonyTypeId(), commLocation.getIndicator().getOriginCountryId());
            cdrData.setOperatorId(internalOp.getId());
            cdrData.setOperatorName(internalOp.getName());
        }

        tariffCalculationService.calculateTariffsForInternal(cdrData, commLocation);
        log.debug("Finished processing INTERNAL call logic. CDR Data: {}", cdrData);
    }

    private InternalCallTypeInfo determineSpecificInternalCallType(CdrData cdrData,
                                                                   LineProcessingContext processingContext) {
        CommunicationLocation currentCommLocation = processingContext.getCommLocation();
        List<String> ignoredAuthCodes = processingContext.getCdrProcessor().getIgnoredAuthCodeDescriptions();
        log.debug("Determining specific internal call type for Calling: {}, Destination: {}",
                cdrData.getCallingPartyNumber(), cdrData.getEffectiveDestinationNumber());

        InternalCallTypeInfo result = new InternalCallTypeInfo();
        result.setTelephonyTypeId(appConfigService.getDefaultTelephonyTypeForUnresolvedInternalCalls());
        result.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(result.getTelephonyTypeId()));
        result.setDestinationIndicatorId(currentCommLocation.getIndicatorId());
        result.setOriginIndicatorId(currentCommLocation.getIndicatorId());

        ExtensionLimits limits = processingContext.getCommLocationExtensionLimits();

        Optional<Employee> originEmpOpt = employeeLookupService.findEmployeeByExtensionOrAuthCode(
                cdrData.getCallingPartyNumber(), null,
                currentCommLocation.getId(), ignoredAuthCodes, processingContext.getExtensionRanges(),
                cdrData.getDateTimeOrigination(), processingContext.getHistoricalData());
        if (originEmpOpt.isEmpty() && CdrUtil.isPossibleExtension(cdrData.getCallingPartyNumber(), limits)) {
            originEmpOpt = employeeLookupService.findEmployeeByExtensionRange(cdrData.getCallingPartyNumber(),
                    currentCommLocation.getId(), processingContext.getExtensionRanges(),
                    cdrData.getDateTimeOrigination(), processingContext.getHistoricalData());
        }
        result.setOriginEmployee(originEmpOpt.orElse(null));

        Optional<Employee> destEmpOpt = employeeLookupService.findEmployeeByExtensionOrAuthCode(
                cdrData.getEffectiveDestinationNumber(), null,
                null, ignoredAuthCodes, processingContext.getExtensionRanges(),
                cdrData.getDateTimeOrigination(), processingContext.getHistoricalData());
        if (destEmpOpt.isEmpty() && CdrUtil.isPossibleExtension(cdrData.getEffectiveDestinationNumber(), limits)) {
            destEmpOpt = employeeLookupService.findEmployeeByExtensionRange(cdrData.getEffectiveDestinationNumber(),
                    null, processingContext.getExtensionRanges(),
                    cdrData.getDateTimeOrigination(), processingContext.getHistoricalData());
        }
        result.setDestinationEmployee(destEmpOpt.orElse(null));

        CommunicationLocation originCommLoc = originEmpOpt.map(Employee::getCommunicationLocation)
                .orElse(currentCommLocation);
        CommunicationLocation destCommLoc = destEmpOpt.map(Employee::getCommunicationLocation).orElse(null);

        if (originCommLoc != null && destCommLoc == null && originEmpOpt.isPresent()) {
            destCommLoc = currentCommLocation;
            log.debug("Destination employee not found for internal call; assuming destination is within current commLocation: {}",
                    currentCommLocation.getDirectory());
        }

        boolean extGlobales = appConfigService.areExtensionsGlobal();
        if (extGlobales && originCommLoc != null && destCommLoc != null &&
                (!Objects.equals(currentCommLocation.getId(), originCommLoc.getId())
                        || !Objects.equals(currentCommLocation.getId(), destCommLoc.getId()))) {
            if (!Objects.equals(currentCommLocation.getId(), originCommLoc.getId())
                    && Objects.equals(currentCommLocation.getId(), destCommLoc.getId())) {
                result.setIgnoreCall(true);
                result.setErrorType(QuarantineErrorType.GLOBAL_EXTENSION_IGNORE); // Specifically apply GLOBAL_EXTENSION_IGNORE
                result.setAdditionalInfo("Global Extension - Incoming internal from another plant");
                return result;
            } else if (!Objects.equals(currentCommLocation.getId(), originCommLoc.getId())
                    && !Objects.equals(currentCommLocation.getId(), destCommLoc.getId())) {
                result.setIgnoreCall(true);
                result.setErrorType(QuarantineErrorType.GLOBAL_EXTENSION_IGNORE); // Specifically apply GLOBAL_EXTENSION_IGNORE
                result.setAdditionalInfo("Global Extension - Internal call between two other plants");
                return result;
            }
        }

        if (destEmpOpt.isEmpty()) {
            Map<String, Long> internalPrefixes = prefixLookupService.getInternalTelephonyTypePrefixes(
                    currentCommLocation.getIndicator().getOriginCountryId());
            boolean prefixMatched = false;
            for (Map.Entry<String, Long> entry : internalPrefixes.entrySet()) {
                if (cdrData.getEffectiveDestinationNumber().startsWith(entry.getKey())) {
                    result.setTelephonyTypeId(entry.getValue());
                    result.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(entry.getValue()));
                    result.setAdditionalInfo(appConfigService.getPrefixText());
                    prefixMatched = true;
                    break;
                }
            }
            if (!prefixMatched) {
                Long defaultUnresolvedType = appConfigService.getDefaultTelephonyTypeForUnresolvedInternalCalls();
                result.setTelephonyTypeId(defaultUnresolvedType);
                result.setTelephonyTypeName(
                        telephonyTypeLookupService.getTelephonyTypeName(result.getTelephonyTypeId()));
                result.setAdditionalInfo(appConfigService.getAssumedText());
            }
        } else if (originCommLoc != null && destCommLoc != null && originCommLoc.getIndicator() != null
                && destCommLoc.getIndicator() != null) {
            Indicator originIndicator = originCommLoc.getIndicator();
            Indicator destIndicator = destCommLoc.getIndicator();
            // Legacy PHP takes the origin/dest indicator from the employee's subdivision office
            // (txt2dbv8.txt:1046 -> $arrSubDirsCliente[$oficinaBuscada]['indicativo']), falling
            // back to the commLocation's indicator when the subdivision has no office row. Using
            // only commLocation caused e.g. Popayan subdivisions hosted in Villavicencio's
            // commLoc to be misclassified (indicator 1045 vs legacy's 156).
            Long originIndicatorId = effectiveIndicatorId(originEmpOpt.orElse(null), originCommLoc);
            Long destIndicatorId = effectiveIndicatorId(destEmpOpt.orElse(null), destCommLoc);
            if (originIndicatorId == null) originIndicatorId = originIndicator.getId();
            if (destIndicatorId == null) destIndicatorId = destIndicator.getId();
            result.setOriginIndicatorId(originIndicatorId);
            result.setDestinationIndicatorId(destIndicatorId);

            Subdivision originSubdivision = originEmpOpt.map(Employee::getSubdivision).orElse(null);
            Long originOfficeId = originSubdivision != null ? originSubdivision.getId() : null;
            Subdivision destSubdivision = destEmpOpt.map(Employee::getSubdivision).orElse(null);
            Long destOfficeId = destSubdivision != null ? destSubdivision.getId() : null;

            if (!Objects.equals(originIndicator.getOriginCountryId(), destIndicator.getOriginCountryId())) {
                result.setTelephonyTypeId(TelephonyTypeEnum.INTERNAL_INTERNATIONAL_IP.getValue());
            } else if (!Objects.equals(originIndicatorId, destIndicatorId)) {
                result.setTelephonyTypeId(TelephonyTypeEnum.NATIONAL_IP.getValue());
            } else if (originOfficeId != null && destOfficeId != null
                    && !Objects.equals(originOfficeId, destOfficeId)) {
                result.setTelephonyTypeId(TelephonyTypeEnum.LOCAL_IP.getValue());
            } else {
                result.setTelephonyTypeId(TelephonyTypeEnum.INTERNAL_SIMPLE.getValue());
            }
            result.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(result.getTelephonyTypeId()));
            if (originEmpOpt.isEmpty()) {
                result.setAdditionalInfo(appConfigService.getAssumedText() + "/" + appConfigService.getOriginText());
            }
        } else {
            result.setTelephonyTypeId(appConfigService.getDefaultTelephonyTypeForUnresolvedInternalCalls());
            result.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(result.getTelephonyTypeId()));
            result.setAdditionalInfo(appConfigService.getAssumedText());
            if (originCommLoc != null && originCommLoc.getIndicator() != null)
                result.setOriginIndicatorId(originCommLoc.getIndicator().getId());
            if (destCommLoc != null && destCommLoc.getIndicator() != null)
                result.setDestinationIndicatorId(destCommLoc.getIndicator().getId());
        }

        if (originEmpOpt.isEmpty() && destEmpOpt.isPresent() &&
                cdrData.getCallDirection() == CallDirection.OUTGOING &&
                destCommLoc != null && Objects.equals(destCommLoc.getId(), currentCommLocation.getId())) {

            boolean isBridge = cdrData.getConferenceIdentifierUsed() != null;
            if (!isBridge) {
                result.setEffectivelyIncoming(true);
            }
        }
        return result;
    }
}