// File: com/infomedia/abacox/telephonypricing/cdr/EmployeeLookupService.java
package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import com.infomedia.abacox.telephonypricing.entity.CommunicationLocation;
import com.infomedia.abacox.telephonypricing.entity.Employee;
import com.infomedia.abacox.telephonypricing.entity.ExtensionRange;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@Log4j2
@RequiredArgsConstructor
public class EmployeeLookupService {

    @PersistenceContext
    private EntityManager entityManager;
    private final CdrConfigService cdrConfigService;
    private final Map<Long, ExtensionLimits> extensionLimitsCache = new ConcurrentHashMap<>();
    // Cache for ExtensionRanges per CommunicationLocation ID for the current stream processing run
    private final Map<Long, List<ExtensionRange>> extensionRangesCache = new ConcurrentHashMap<>();


    @Transactional(readOnly = true)
    public void resetCachesForNewStream() {
        extensionLimitsCache.clear();
        extensionRangesCache.clear();
        log.info("ExtensionLimits and ExtensionRanges caches cleared for new stream processing.");
    }

    @Transactional(readOnly = true)
    public ExtensionLimits getExtensionLimits(CommunicationLocation commLocation) {
        if (commLocation == null || commLocation.getId() == null) {
            log.warn("getExtensionLimits called with null or invalid commLocation.");
            return new ExtensionLimits();
        }
        return this.extensionLimitsCache.computeIfAbsent(commLocation.getId(), id -> {
            log.debug("Extension limits not found in cache for CommLocation ID: {}. Fetching.", id);
            if (commLocation.getIndicator() != null && commLocation.getIndicator().getOriginCountryId() != null) {
                return getExtensionLimitsLookup(
                        commLocation.getIndicator().getOriginCountryId(),
                        id,
                        commLocation.getPlantTypeId()
                );
            }
            log.warn("Cannot fetch extension limits: Indicator or OriginCountryId is null for CommLocation ID: {}", id);
            return new ExtensionLimits();
        });
    }


    /**
     * Finds an employee by extension or auth code. If not found by direct match,
     * and the extension is valid, it attempts to find a match in extension ranges.
     * If a range matches and auto-creation is enabled, the employee will be persisted.
     */
    @Transactional // Keep @Transactional for potential employee creation
    public Optional<Employee> findEmployeeByExtensionOrAuthCode(String extension, String authCode,
                                                                Long commLocationIdContext) {
        StringBuilder queryStr = new StringBuilder("SELECT e.* FROM employee e ");
        queryStr.append(" LEFT JOIN communication_location cl ON e.communication_location_id = cl.id ");
        queryStr.append(" WHERE e.active = true ");

        boolean hasAuthCode = authCode != null && !authCode.isEmpty();
        String cleanedExtension = (extension != null) ? CdrUtil.cleanPhoneNumber(extension, null, false).getCleanedNumber() : null;
        if (cleanedExtension != null && cleanedExtension.startsWith("+")) {
            cleanedExtension = cleanedExtension.substring(1);
        }
        boolean hasExtension = cleanedExtension != null && !cleanedExtension.isEmpty();

        boolean isAuthCodeIgnoredType = hasAuthCode && cdrConfigService.getIgnoredAuthCodeDescriptions().stream()
                .anyMatch(ignored -> ignored.equalsIgnoreCase(authCode));

        if (hasAuthCode && !isAuthCodeIgnoredType) {
            queryStr.append(" AND e.auth_code = :authCode ");
        } else if (hasExtension) {
            queryStr.append(" AND e.extension = :extension ");
        } else {
            log.debug("No valid extension or auth code provided for employee lookup.");
            return Optional.empty();
        }

        boolean searchGlobally = (hasAuthCode && !isAuthCodeIgnoredType) ?
                                 cdrConfigService.areAuthCodesGlobal() :
                                 cdrConfigService.areExtensionsGlobal();

        if (!searchGlobally && commLocationIdContext != null) {
            queryStr.append(" AND e.communication_location_id = :commLocationIdContext ");
        }
        queryStr.append(" AND (cl.id IS NULL OR cl.active = true) ");


        if (searchGlobally && commLocationIdContext != null) {
            queryStr.append(" ORDER BY CASE WHEN e.communication_location_id = :commLocationIdContext THEN 0 ELSE 1 END, e.created_date DESC LIMIT 1");
        } else {
            queryStr.append(" ORDER BY e.created_date DESC LIMIT 1");
        }


        jakarta.persistence.Query nativeQuery = entityManager.createNativeQuery(queryStr.toString(), Employee.class);

        if (hasAuthCode && !isAuthCodeIgnoredType) {
            nativeQuery.setParameter("authCode", authCode);
        } else if (hasExtension) {
            nativeQuery.setParameter("extension", cleanedExtension);
        }

        if (!searchGlobally && commLocationIdContext != null) {
            nativeQuery.setParameter("commLocationIdContext", commLocationIdContext);
        } else if (searchGlobally && commLocationIdContext != null) {
            nativeQuery.setParameter("commLocationIdContext", commLocationIdContext);
        }


        try {
            Employee employee = (Employee) nativeQuery.getSingleResult();
            log.debug("Found existing employee by extension/auth_code: {}", employee.getId());
            return Optional.of(employee);
        } catch (jakarta.persistence.NoResultException e) {
            boolean phpExtensionValida = hasExtension &&
                                         (!cleanedExtension.startsWith("0") || cleanedExtension.equals("0")) &&
                                         cleanedExtension.matches("^[0-9#*+]+$");

            if (phpExtensionValida) {
                Optional<Employee> conceptualEmployeeOpt = findEmployeeByExtensionRange(cleanedExtension, commLocationIdContext);
                if (conceptualEmployeeOpt.isPresent()) {
                    Employee conceptualEmployee = conceptualEmployeeOpt.get();
                    if (conceptualEmployee.getId() == null && cdrConfigService.createEmployeesAutomaticallyFromRange()) {
                        log.info("Persisting new employee for extension {} from range, CommLocation ID: {}",
                                conceptualEmployee.getExtension(), conceptualEmployee.getCommunicationLocationId());
                        entityManager.persist(conceptualEmployee);
                        return Optional.of(conceptualEmployee);
                    } else if (conceptualEmployee.getId() != null) {
                        return Optional.of(conceptualEmployee);
                    }
                }
            }
            return Optional.empty();
        }
    }

    private Employee createEmployeeFromRange(String extension, Long subdivisionId, Long commLocationId, String namePrefix) {
        Employee newEmployee = new Employee();
        newEmployee.setExtension(extension);
        newEmployee.setName((namePrefix != null ? namePrefix : "Ext") + " " + extension);
        newEmployee.setSubdivisionId(subdivisionId);
        newEmployee.setCommunicationLocationId(commLocationId);
        newEmployee.setActive(true);
        newEmployee.setAuthCode("");
        newEmployee.setEmail("");
        newEmployee.setPhone("");
        newEmployee.setAddress("");
        newEmployee.setIdNumber("");
        log.info("Conceptually created new employee object for extension {} from range.", extension);
        return newEmployee;
    }

    @Transactional(readOnly = true)
    public ExtensionLimits getExtensionLimitsLookup(Long originCountryId, Long commLocationId, Long plantTypeId) {
        int finalMinVal = 100;
        int finalMaxVal = CdrConfigService.ACUMTOTAL_MAX_EXTENSION_LENGTH_FOR_INTERNAL_CHECK;

        int maxLenEmployees = 0;
        int minLenEmployees = Integer.MAX_VALUE;
        boolean empLenSet = false;

        String maxAllowedLenStr = String.valueOf(CdrConfigService.ACUMTOTAL_MAX_EXTENSION_LENGTH_FOR_INTERNAL_CHECK);
        int maxStandardExtLength = maxAllowedLenStr.length() - 1;
        if (maxStandardExtLength < 1) maxStandardExtLength = 1;

        StringBuilder empQueryBuilder = new StringBuilder(
            "SELECT CAST(LENGTH(e.extension) AS INTEGER) as ext_len " +
            "FROM employee e " +
            "JOIN communication_location cl ON e.communication_location_id = cl.id " +
            "JOIN indicator i ON cl.indicator_id = i.id " +
            "WHERE e.active = true AND cl.active = true AND i.active = true " +
            "  AND e.extension ~ '^[1-9][0-9]*$' " +
            "  AND LENGTH(e.extension) BETWEEN 1 AND :maxStandardExtLength ");
        if (originCountryId != null) empQueryBuilder.append(" AND i.origin_country_id = :originCountryId ");
        if (commLocationId != null) empQueryBuilder.append(" AND e.communication_location_id = :commLocationId ");
        if (plantTypeId != null) empQueryBuilder.append(" AND cl.plant_type_id = :plantTypeId ");

        jakarta.persistence.Query empQuery = entityManager.createNativeQuery(empQueryBuilder.toString());
        empQuery.setParameter("maxStandardExtLength", maxStandardExtLength);
        if (originCountryId != null) empQuery.setParameter("originCountryId", originCountryId);
        if (commLocationId != null) empQuery.setParameter("commLocationId", commLocationId);
        if (plantTypeId != null) empQuery.setParameter("plantTypeId", plantTypeId);

        List<Number> empLengths = empQuery.getResultList();
        for (Number lenNum : empLengths) {
            int len = lenNum.intValue();
            if (len > maxLenEmployees) maxLenEmployees = len;
            if (len < minLenEmployees) minLenEmployees = len;
            empLenSet = true;
        }
        if (!empLenSet) minLenEmployees = 0;

        int maxLenRanges = 0;
        int minLenRanges = Integer.MAX_VALUE;
        boolean rangeLenSet = false;

        StringBuilder rangeQueryBuilder = new StringBuilder(
            "SELECT CAST(LENGTH(er.range_start::text) AS INTEGER) as len_desde, CAST(LENGTH(er.range_end::text) AS INTEGER) as len_hasta " +
            "FROM extension_range er " +
            "JOIN communication_location cl ON er.comm_location_id = cl.id " +
            "JOIN indicator i ON cl.indicator_id = i.id " +
            "WHERE er.active = true AND cl.active = true AND i.active = true " +
            "  AND er.range_start::text ~ '^[0-9]+$' AND er.range_end::text ~ '^[0-9]+$' " +
            "  AND LENGTH(er.range_start::text) BETWEEN 1 AND :maxStandardExtLength " +
            "  AND LENGTH(er.range_end::text) BETWEEN 1 AND :maxStandardExtLength ");
        if (originCountryId != null) rangeQueryBuilder.append(" AND i.origin_country_id = :originCountryId ");
        if (commLocationId != null) rangeQueryBuilder.append(" AND er.comm_location_id = :commLocationId ");
        if (plantTypeId != null) rangeQueryBuilder.append(" AND cl.plant_type_id = :plantTypeId ");

        jakarta.persistence.Query rangeQuery = entityManager.createNativeQuery(rangeQueryBuilder.toString());
        rangeQuery.setParameter("maxStandardExtLength", maxStandardExtLength);
        if (originCountryId != null) rangeQuery.setParameter("originCountryId", originCountryId);
        if (commLocationId != null) rangeQuery.setParameter("commLocationId", commLocationId);
        if (plantTypeId != null) rangeQuery.setParameter("plantTypeId", plantTypeId);

        List<Object[]> rangeLengthPairs = rangeQuery.getResultList();
        for (Object[] pair : rangeLengthPairs) {
            int lenDesde = ((Number) pair[0]).intValue();
            int lenHasta = ((Number) pair[1]).intValue();
            if (lenHasta > maxLenRanges) maxLenRanges = lenHasta;
            if (lenDesde < minLenRanges) minLenRanges = lenDesde;
            rangeLenSet = true;
        }
        if (!rangeLenSet) minLenRanges = 0;

        int effectiveMinLen = Integer.MAX_VALUE;
        int effectiveMaxLen = 0;

        if (empLenSet) {
            effectiveMinLen = Math.min(effectiveMinLen, minLenEmployees);
            effectiveMaxLen = Math.max(effectiveMaxLen, maxLenEmployees);
        }
        if (rangeLenSet) {
            effectiveMinLen = Math.min(effectiveMinLen, minLenRanges);
            effectiveMaxLen = Math.max(effectiveMaxLen, maxLenRanges);
        }

        if (effectiveMinLen == Integer.MAX_VALUE) effectiveMinLen = 0;

        if (effectiveMaxLen > 0) {
            finalMaxVal = Integer.parseInt("9".repeat(effectiveMaxLen));
        }
        if (effectiveMinLen > 0) {
            finalMinVal = Integer.parseInt("1" + "0".repeat(Math.max(0, effectiveMinLen - 1)));
        }

        if (finalMinVal > finalMaxVal && finalMaxVal > 0 && effectiveMinLen > 0 && effectiveMaxLen > 0) {
            finalMinVal = finalMaxVal;
        }


        StringBuilder specialExtQueryBuilder = new StringBuilder(
            "SELECT DISTINCT e.extension FROM employee e " +
            "JOIN communication_location cl ON e.communication_location_id = cl.id " +
            "JOIN indicator i ON cl.indicator_id = i.id " +
            "WHERE e.active = true AND cl.active = true AND i.active = true " +
            "  AND e.extension NOT LIKE '%-%' " +
            "  AND (LENGTH(e.extension) >= :maxExtStandardLenForFullList OR e.extension LIKE '0%' OR e.extension LIKE '*%' OR e.extension LIKE '#%') ");
        if (originCountryId != null) specialExtQueryBuilder.append(" AND i.origin_country_id = :originCountryId ");
        if (commLocationId != null) specialExtQueryBuilder.append(" AND e.communication_location_id = :commLocationId ");
        if (plantTypeId != null) specialExtQueryBuilder.append(" AND cl.plant_type_id = :plantTypeId ");

        jakarta.persistence.Query specialExtQuery = entityManager.createNativeQuery(specialExtQueryBuilder.toString(), String.class);
        specialExtQuery.setParameter("maxExtStandardLenForFullList", maxAllowedLenStr.length());
        if (originCountryId != null) specialExtQuery.setParameter("originCountryId", originCountryId);
        if (commLocationId != null) specialExtQuery.setParameter("commLocationId", commLocationId);
        if (plantTypeId != null) specialExtQuery.setParameter("plantTypeId", plantTypeId);

        List<String> specialFullExtensions = specialExtQuery.getResultList();

        log.debug("Calculated extension limits: minVal={}, maxVal={}, specialCount={}", finalMinVal, finalMaxVal, specialFullExtensions.size());
        return new ExtensionLimits(finalMinVal, finalMaxVal, specialFullExtensions);
    }

    public boolean isPossibleExtension(String extensionNumber, ExtensionLimits limits) {
        if (extensionNumber == null || extensionNumber.isEmpty()) {
            return false;
        }
        String cleanedExt = CdrUtil.cleanPhoneNumber(extensionNumber, null, false).getCleanedNumber();
        if (cleanedExt.startsWith("+")) cleanedExt = cleanedExt.substring(1);

        if (limits.getSpecialFullExtensions() != null && limits.getSpecialFullExtensions().contains(cleanedExt)) {
            return true;
        }

        boolean phpExtensionValidaForNumericRange = (!cleanedExt.startsWith("0") || cleanedExt.equals("0")) &&
                                                    cleanedExt.matches("\\d+");

        if (phpExtensionValidaForNumericRange) {
            try {
                long extNumValue = Long.parseLong(cleanedExt);
                return extNumValue >= limits.getMinLength() && extNumValue <= limits.getMaxLength();
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }

    @Transactional(readOnly = true)
    public Optional<Employee> findEmployeeByExtensionRange(String extension, Long commLocationId) {
        if (extension == null || !extension.matches("\\d+")) {
            return Optional.empty();
        }
        long extNum;
        try {
            extNum = Long.parseLong(extension);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }

        long commLocationIdContext = commLocationId != null ? commLocationId : 0L; // Use 0L for global context

        boolean searchRangesGlobally = cdrConfigService.areExtensionsGlobal();

        // Use cached ranges if available for the context (global or specific commLocation)
        Long cacheKey = searchRangesGlobally ? 0L : commLocationIdContext; // 0L for global cache key

        List<ExtensionRange> rangesToSearch = extensionRangesCache.computeIfAbsent(cacheKey, k -> {
            log.debug("Extension ranges not in cache for key {}. Fetching.", k);
            StringBuilder queryStrBuilder = new StringBuilder(
                "SELECT er.* FROM extension_range er " +
                "JOIN communication_location cl ON er.comm_location_id = cl.id " +
                "WHERE er.active = true AND cl.active = true ");

            if (!searchRangesGlobally) {
                queryStrBuilder.append("AND er.comm_location_id = :commLocationIdContext ");
            }
            // If searchRangesGlobally, we don't filter by comm_location_id here, but might order by it later.
            // PHP: ORDER BY RANGOEXT_HISTODESDE DESC, RANGOEXT_DESDE DESC, RANGOEXT_HASTA ASC
            // Historical part is omitted for now.
            queryStrBuilder.append("ORDER BY (er.range_end - er.range_start) ASC, er.created_date DESC");

            Query nativeQuery = entityManager.createNativeQuery(queryStrBuilder.toString(), ExtensionRange.class);
            if (!searchRangesGlobally) {
                nativeQuery.setParameter("commLocationIdContext", commLocationIdContext);
            }
            return nativeQuery.getResultList();
        });

        // Filter the (potentially cached) list
        List<ExtensionRange> matchingRanges = rangesToSearch.stream()
            .filter(er -> extNum >= er.getRangeStart() && extNum <= er.getRangeEnd())
            .collect(Collectors.toList());

        if (searchRangesGlobally) {
            matchingRanges.sort(Comparator
                .comparing((ExtensionRange er) -> !Objects.equals(er.getCommLocationId(), commLocationIdContext)) // false (0) for match, true (1) for non-match
                .thenComparingLong(er -> er.getRangeEnd() - er.getRangeStart()) // tighter range
                .thenComparing(ExtensionRange::getCreatedDate, Comparator.nullsLast(Comparator.reverseOrder()))
            );
        }
        // If not global or no context, existing sort (tighter range, newer) is fine.

        if (!matchingRanges.isEmpty()) {
            ExtensionRange matchedRange = matchingRanges.get(0); // Get the best match after sorting
            log.debug("Extension {} matched range: {}", extension, matchedRange.getId());
            Employee conceptualEmployee = createEmployeeFromRange(
                    extension,
                    matchedRange.getSubdivisionId(),
                    matchedRange.getCommLocationId(),
                    matchedRange.getPrefix()
            );
            if (matchedRange.getCommLocationId() != null) {
                CommunicationLocation cl = entityManager.find(CommunicationLocation.class, matchedRange.getCommLocationId());
                conceptualEmployee.setCommunicationLocation(cl);
            }
            return Optional.of(conceptualEmployee);
        }
        return Optional.empty();
    }

    @Transactional(readOnly = true)
    protected Long getPlantTypeIdForCommLocation(Long commLocationId) {
        if (commLocationId == null) return null;
        try {
            return entityManager.createQuery("SELECT cl.plantTypeId FROM CommunicationLocation cl WHERE cl.id = :id", Long.class)
                    .setParameter("id", commLocationId)
                    .getSingleResult();
        } catch (jakarta.persistence.NoResultException e) {
            return null;
        }
    }
}