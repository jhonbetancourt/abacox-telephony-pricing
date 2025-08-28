// File: com/infomedia/abacox/telephonypricing/component/cdrprocessing/EmployeeLookupService.java
package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import com.infomedia.abacox.telephonypricing.db.entity.CommunicationLocation;
import com.infomedia.abacox.telephonypricing.db.entity.Employee;
import com.infomedia.abacox.telephonypricing.db.entity.ExtensionRange;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Log4j2
@RequiredArgsConstructor
public class EmployeeLookupService {

    @PersistenceContext
    private EntityManager entityManager;
    private final CdrConfigService cdrConfigService;

    private Employee createEmployeeFromRange(String extension, Long subdivisionId, Long commLocationId, String namePrefix) {
        Employee newEmployee = new Employee();
        newEmployee.setExtension(extension);

        // Use the prefix from the range if provided, otherwise use the default from config service
        String prefixToUse = (namePrefix != null && !namePrefix.isEmpty())
                ? namePrefix
                : cdrConfigService.getEmployeeNamePrefixFromRange(); // Using the new config value

        newEmployee.setName(prefixToUse + " " + extension);
        newEmployee.setSubdivisionId(subdivisionId);
        newEmployee.setCommunicationLocationId(commLocationId);
        newEmployee.setActive(true);
        newEmployee.setAuthCode("");
        newEmployee.setEmail("");
        newEmployee.setPhone("");
        newEmployee.setAddress("");
        newEmployee.setIdNumber("");
        log.debug("Conceptually created new employee object for extension {} from range.", extension);
        return newEmployee;
    }

    /**
     * Finds an employee by extension or auth code. If not found by direct match,
     * and the extension is valid, it attempts to find a match in extension ranges.
     * If a range matches and auto-creation is enabled, the employee will be persisted.
     */
    @Transactional // Keep @Transactional for potential employee creation
    public Optional<Employee> findEmployeeByExtensionOrAuthCode(String extension, String authCode,
                                                                Long commLocationIdContext, List<String> ignoredAuthCodeDescriptions, Map<Long, List<ExtensionRange>> extensionRanges) {
        StringBuilder queryStr = new StringBuilder("SELECT e.* FROM employee e ");
        queryStr.append(" LEFT JOIN communication_location cl ON e.communication_location_id = cl.id ");
        queryStr.append(" WHERE e.active = true ");

        boolean hasAuthCode = authCode != null && !authCode.isEmpty();
        String cleanedExtension = (extension != null) ? CdrUtil.cleanPhoneNumber(extension, null, false).getCleanedNumber() : null;
        if (cleanedExtension != null && cleanedExtension.startsWith("+")) {
            cleanedExtension = cleanedExtension.substring(1);
        }
        boolean hasExtension = cleanedExtension != null && !cleanedExtension.isEmpty();

        boolean isAuthCodeIgnoredType = hasAuthCode && ignoredAuthCodeDescriptions.stream()
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
                Optional<Employee> conceptualEmployeeOpt = findEmployeeByExtensionRange(cleanedExtension, commLocationIdContext, extensionRanges);
                if (conceptualEmployeeOpt.isPresent()) {
                    Employee conceptualEmployee = conceptualEmployeeOpt.get();
                    if (conceptualEmployee.getId() == null && cdrConfigService.createEmployeesAutomaticallyFromRange()) {
                        log.debug("Persisting new employee for extension {} from range, CommLocation ID: {}",
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

    /**
     * Fetches the ExtensionLimits for all active CommunicationLocations from the database in a single, efficient operation.
     * This method performs bulk lookups and processes the data in memory to avoid N+1 query issues.
     * It bypasses the internal cache.
     *
     * @return A map where the key is the CommunicationLocation ID and the value is its calculated ExtensionLimits.
     */
    @Transactional(readOnly = true)
    public Map<Long, ExtensionLimits> getExtensionLimits() {
        log.debug("Fetching all extension limits for all active Communication Locations using bulk operations.");

        // 1. Initialize a result map with default limits for all active locations.
        // This ensures every location has an entry, even if it has no employees or ranges.
        String commLocationQuery = "SELECT cl FROM CommunicationLocation cl WHERE cl.active = true";
        List<CommunicationLocation> allCommLocations = entityManager.createQuery(commLocationQuery, CommunicationLocation.class).getResultList();
        Map<Long, ExtensionLimits> resultMap = allCommLocations.stream()
                .collect(Collectors.toMap(CommunicationLocation::getId, id -> new ExtensionLimits()));

        if (allCommLocations.isEmpty()) {
            log.debug("No active Communication Locations found to fetch extension limits for.");
            return Collections.emptyMap();
        }

        String maxAllowedLenStr = String.valueOf(CdrConfigService.MAX_EXTENSION_LENGTH_FOR_INTERNAL_CHECK);
        int maxStandardExtLength = maxAllowedLenStr.length() - 1;
        if (maxStandardExtLength < 1) maxStandardExtLength = 1;

        // 2. Bulk fetch Employee extension lengths
        String empLenQueryStr = "SELECT e.communication_location_id as comm_id, " +
                "  CAST(MIN(LENGTH(e.extension)) AS INTEGER) as min_len, " +
                "  CAST(MAX(LENGTH(e.extension)) AS INTEGER) as max_len " +
                "FROM employee e " +
                "JOIN communication_location cl ON e.communication_location_id = cl.id " +
                "WHERE e.active = true AND cl.active = true " +
                "  AND e.extension ~ '^[1-9][0-9]*$' " +
                "  AND LENGTH(e.extension) BETWEEN 1 AND :maxStandardExtLength " +
                "GROUP BY e.communication_location_id";
        Query empLenQuery = entityManager.createNativeQuery(empLenQueryStr, Tuple.class);
        empLenQuery.setParameter("maxStandardExtLength", maxStandardExtLength);
        List<Tuple> empLenResults = empLenQuery.getResultList();

        for (Tuple row : empLenResults) {
            Long commId = row.get("comm_id", Number.class).longValue();
            int minLen = row.get("min_len", Number.class).intValue();
            int maxLen = row.get("max_len", Number.class).intValue();
            resultMap.computeIfPresent(commId, (k, v) -> v.updateLengths(minLen, maxLen));
        }
        log.debug("Processed {} employee length groups.", empLenResults.size());

        // 3. Bulk fetch ExtensionRange lengths
        String rangeLenQueryStr = "SELECT er.comm_location_id as comm_id, " +
                "  CAST(MIN(LENGTH(er.range_start::text)) AS INTEGER) as min_len, " +
                "  CAST(MAX(LENGTH(er.range_end::text)) AS INTEGER) as max_len " +
                "FROM extension_range er " +
                "JOIN communication_location cl ON er.comm_location_id = cl.id " +
                "WHERE er.active = true AND cl.active = true " +
                "  AND er.range_start::text ~ '^[0-9]+$' AND er.range_end::text ~ '^[0-9]+$' " +
                "  AND LENGTH(er.range_start::text) BETWEEN 1 AND :maxStandardExtLength " +
                "  AND LENGTH(er.range_end::text) BETWEEN 1 AND :maxStandardExtLength " +
                "GROUP BY er.comm_location_id";
        Query rangeLenQuery = entityManager.createNativeQuery(rangeLenQueryStr, Tuple.class);
        rangeLenQuery.setParameter("maxStandardExtLength", maxStandardExtLength);
        List<Tuple> rangeLenResults = rangeLenQuery.getResultList();

        for (Tuple row : rangeLenResults) {
            Long commId = row.get("comm_id", Number.class).longValue();
            int minLen = row.get("min_len", Number.class).intValue();
            int maxLen = row.get("max_len", Number.class).intValue();
            resultMap.computeIfPresent(commId, (k, v) -> v.updateLengths(minLen, maxLen));
        }
        log.debug("Processed {} extension range length groups.", rangeLenResults.size());

        // 4. Bulk fetch Special Extensions
        String specialExtQueryStr = "SELECT e.communication_location_id as comm_id, e.extension " +
                "FROM employee e " +
                "JOIN communication_location cl ON e.communication_location_id = cl.id " +
                "WHERE e.active = true AND cl.active = true " +
                "  AND e.extension NOT LIKE '%-%' " +
                "  AND (LENGTH(e.extension) >= :maxExtStandardLenForFullList OR e.extension LIKE '0%' OR e.extension LIKE '*%' OR e.extension LIKE '#%')";
        Query specialExtQuery = entityManager.createNativeQuery(specialExtQueryStr, Tuple.class);
        specialExtQuery.setParameter("maxExtStandardLenForFullList", maxAllowedLenStr.length());
        List<Tuple> specialExtResults = specialExtQuery.getResultList();

        // Group results by comm_id in memory
        Map<Long, List<String>> specialExtensionsByCommId = specialExtResults.stream()
                .collect(Collectors.groupingBy(
                        tuple -> tuple.get("comm_id", Number.class).longValue(),
                        Collectors.mapping(tuple -> tuple.get("extension", String.class), Collectors.toList())
                ));

        specialExtensionsByCommId.forEach((commId, extensions) ->
                resultMap.computeIfPresent(commId, (k, v) -> {
                    v.setSpecialFullExtensions(extensions);
                    return v;
                })
        );
        log.debug("Processed special extensions for {} locations.", specialExtensionsByCommId.size());

        // 5. Finalize the numeric min/max values from the collected lengths
        resultMap.values().forEach(ExtensionLimits::calculateFinalMinMaxValues);
        log.debug("Finished fetching and calculating all extension limits. Found limits for {} locations.", resultMap.size());

        return resultMap;
    }

    /**
     * Finds a conceptual employee by checking if an extension falls within a given set of ranges.
     * This version receives the extension ranges as a parameter, bypassing the internal cache.
     *
     * @param extension The extension number to check.
     * @param commLocationId The context of the current communication location, used for global vs. local search logic.
     * @param extensionRanges A pre-fetched map of all relevant extension ranges.
     * @return An Optional containing a conceptual Employee if a match is found.
     */
    @Transactional(readOnly = true)
    public Optional<Employee> findEmployeeByExtensionRange(String extension, Long commLocationId, Map<Long, List<ExtensionRange>> extensionRanges) {
        if (extension == null || !extension.matches("\\d+") || extensionRanges == null || extensionRanges.isEmpty()) {
            return Optional.empty();
        }
        long extNum;
        try {
            extNum = Long.parseLong(extension);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }

        boolean searchRangesGlobally = cdrConfigService.areExtensionsGlobal();
        List<ExtensionRange> matchingRanges = new ArrayList<>();

        if (searchRangesGlobally) {
            // If global, check all ranges from all comm locations
            extensionRanges.values().stream()
                    .flatMap(List::stream) // Flatten the lists of ranges from all locations
                    .filter(er -> extNum >= er.getRangeStart() && extNum <= er.getRangeEnd())
                    .forEach(matchingRanges::add);

            // Sort to prioritize the range from the current context commLocationId
            matchingRanges.sort(Comparator
                    .comparing((ExtensionRange er) -> !Objects.equals(er.getCommLocationId(), commLocationId)) // false (0) for match, true (1) for non-match
                    .thenComparingLong(er -> er.getRangeEnd() - er.getRangeStart()) // then by tighter range
                    .thenComparing(ExtensionRange::getCreatedDate, Comparator.nullsLast(Comparator.reverseOrder()))
            );
        } else {
            // If not global, only check ranges for the specific commLocationId
            List<ExtensionRange> rangesForLocation = extensionRanges.get(commLocationId);
            if (rangesForLocation != null) {
                rangesForLocation.stream()
                        .filter(er -> extNum >= er.getRangeStart() && extNum <= er.getRangeEnd())
                        .forEach(matchingRanges::add);
                // The list is already sorted by tightness/date from the fetch query
            }
        }

        if (!matchingRanges.isEmpty()) {
            ExtensionRange bestMatch = matchingRanges.get(0); // The best match is the first one after sorting
            log.debug("Extension {} matched range (via parameter): {}", extension, bestMatch.getId());
            Employee conceptualEmployee = createEmployeeFromRange(
                    extension,
                    bestMatch.getSubdivisionId(),
                    bestMatch.getCommLocationId(),
                    bestMatch.getPrefix()
            );
            // Eagerly fetch the associated CommunicationLocation for the conceptual employee
            if (bestMatch.getCommLocationId() != null) {
                CommunicationLocation cl = entityManager.find(CommunicationLocation.class, bestMatch.getCommLocationId());
                conceptualEmployee.setCommunicationLocation(cl);
            }
            return Optional.of(conceptualEmployee);
        }

        return Optional.empty();
    }

    /**
     * Fetches all active extension ranges from the database, organized by CommunicationLocation ID.
     *
     * @return A map where the key is the CommunicationLocation ID and the value is a list of its ExtensionRanges.
     */
    @Transactional(readOnly = true)
    public Map<Long, List<ExtensionRange>> getExtensionRanges() {
        log.debug("Fetching all active extension ranges from the database (cache bypassed).");

        String queryStr = "SELECT er.* FROM extension_range er " +
                "JOIN communication_location cl ON er.comm_location_id = cl.id " +
                "WHERE er.active = true AND cl.active = true " +
                // PHP: ORDER BY RANGOEXT_HISTODESDE DESC, RANGOEXT_DESDE DESC, RANGOEXT_HASTA ASC
                // Historical part is omitted for now. Sorting by range size and creation date is a good practice.
                "ORDER BY (er.range_end - er.range_start) ASC, er.created_date DESC";

        Query nativeQuery = entityManager.createNativeQuery(queryStr, ExtensionRange.class);
        List<ExtensionRange> allRanges = nativeQuery.getResultList();

        // Group the results by CommunicationLocation ID
        return allRanges.stream()
                .collect(Collectors.groupingBy(ExtensionRange::getCommLocationId));
    }
}