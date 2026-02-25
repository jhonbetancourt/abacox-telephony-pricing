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

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Log4j2
@RequiredArgsConstructor
public class EmployeeLookupService {

    @PersistenceContext
    private EntityManager entityManager;
    private final CdrConfigService cdrConfigService;

    @SuppressWarnings("unchecked")
    public HistoricalDataContainer prefetchHistoricalData(Set<String> extensions, Set<String> authCodes) {
        log.debug("Pre-fetching historical data for {} extensions and {} auth codes", extensions.size(),
                authCodes.size());

        Set<String> cleanedExtensions = extensions.stream()
                .filter(Objects::nonNull)
                .map(CdrUtil::cleanExtension)
                .collect(Collectors.toSet());

        Set<String> validAuthCodes = authCodes.stream()
                .filter(Objects::nonNull)
                .filter(ac -> !ac.trim().isEmpty())
                .collect(Collectors.toSet());

        // Step 1: Identify identifiers that HAVE history (any record with
        // historyControlId)
        String historyCheckQuery = "SELECT DISTINCT extension, auth_code FROM employee WHERE history_control_id IS NOT NULL "
                +
                "AND (extension IN (:extensions) OR auth_code IN (:authCodes))";

        jakarta.persistence.Query checkQuery = entityManager.createNativeQuery(historyCheckQuery, Tuple.class);
        checkQuery.setParameter("extensions",
                cleanedExtensions.isEmpty() ? Collections.singleton("-1") : cleanedExtensions);
        checkQuery.setParameter("authCodes", validAuthCodes.isEmpty() ? Collections.singleton("-1") : validAuthCodes);

        List<Tuple> historyCheckResults = checkQuery.getResultList();
        Set<String> extensionsWithHistory = new HashSet<>();
        Set<String> authCodesWithHistory = new HashSet<>();

        for (Tuple t : historyCheckResults) {
            String ext = t.get(0, String.class);
            String ac = t.get(1, String.class);
            if (ext != null)
                extensionsWithHistory.add(ext);
            if (ac != null)
                authCodesWithHistory.add(ac);
        }

        HistoricalDataContainer container = new HistoricalDataContainer(extensionsWithHistory, authCodesWithHistory);

        // Step 2: Fetch all versions for these identifiers
        if (!extensionsWithHistory.isEmpty() || !authCodesWithHistory.isEmpty()) {
            String fetchAllQuery = "SELECT e.* FROM employee e WHERE e.extension IN (:extensions) OR e.auth_code IN (:authCodes) "
                    +
                    "ORDER BY e.history_since DESC";

            jakarta.persistence.Query fetchQuery = entityManager.createNativeQuery(fetchAllQuery, Employee.class);
            fetchQuery.setParameter("extensions",
                    extensionsWithHistory.isEmpty() ? Collections.singleton("-1") : extensionsWithHistory);
            fetchQuery.setParameter("authCodes",
                    authCodesWithHistory.isEmpty() ? Collections.singleton("-1") : authCodesWithHistory);

            List<Employee> allVersions = fetchQuery.getResultList();

            // Group by HistoryControlId
            Map<Long, List<Employee>> versionsByGroup = allVersions.stream()
                    .filter(e -> e.getHistoryControlId() != null)
                    .collect(Collectors.groupingBy(Employee::getHistoryControlId));

            for (Map.Entry<Long, List<Employee>> entry : versionsByGroup.entrySet()) {
                HistoricalTimeline<Employee> timeline = new HistoricalTimeline<>(entry.getKey(), entry.getValue());
                // Map the timeline to all extensions/auth codes it represents
                entry.getValue().forEach(e -> {
                    if (e.getExtension() != null)
                        container.addEmployeeTimelineByExtension(e.getExtension(), timeline);
                    if (e.getAuthCode() != null)
                        container.addEmployeeTimelineByAuthCode(e.getAuthCode(), timeline);
                });
            }
        }

        // Step 3: Fetch Extension Ranges (Pre-fetch all, as they are few typically)
        String fetchRangesQuery = "SELECT er.* FROM extension_range er ORDER BY er.history_since DESC";
        List<ExtensionRange> allRanges = entityManager.createNativeQuery(fetchRangesQuery, ExtensionRange.class)
                .getResultList();

        Map<Long, List<ExtensionRange>> rangesByGroup = allRanges.stream()
                .filter(er -> er.getHistoryControlId() != null)
                .collect(Collectors.groupingBy(ExtensionRange::getHistoryControlId));

        for (Map.Entry<Long, List<ExtensionRange>> entry : rangesByGroup.entrySet()) {
            container.addRangeTimeline(entry.getKey(), new HistoricalTimeline<>(entry.getKey(), entry.getValue()));
        }

        return container;
    }

    private Employee createEmployeeFromRange(String extension, Long subdivisionId, Long commLocationId,
            String namePrefix) {
        Employee newEmployee = new Employee();
        newEmployee.setExtension(extension);

        // Use the prefix from the range if provided, otherwise use the default from
        // config service
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
     * Finds an employee by extension or auth code.
     * Logic:
     * 1. Tries to match by Auth Code (if present and valid).
     * 2. If Auth Code match fails (or wasn't provided), tries to match by
     * Extension.
     * 3. If both fail, attempts to find a match in Extension Ranges.
     * 4. If a range matches and auto-creation is enabled, the employee will be
     * persisted.
     */
    @Transactional
    public Optional<Employee> findEmployeeByExtensionOrAuthCode(String extension, String authCode,
            Long commLocationIdContext,
            List<String> ignoredAuthCodeDescriptions,
            Map<Long, List<ExtensionRange>> extensionRanges,
            LocalDateTime callTimestamp,
            HistoricalDataContainer historicalData) {

        // --- 1. Preparation ---
        boolean hasAuthCode = authCode != null && !authCode.isEmpty();
        String cleanedExtension = (extension != null)
                ? CdrUtil.cleanPhoneNumber(extension, null, false).getCleanedNumber()
                : null;
        if (cleanedExtension != null && cleanedExtension.startsWith("+")) {
            cleanedExtension = cleanedExtension.substring(1);
        }
        boolean hasExtension = cleanedExtension != null && !cleanedExtension.isEmpty();

        boolean isAuthCodeIgnoredType = hasAuthCode && ignoredAuthCodeDescriptions.stream()
                .anyMatch(ignored -> ignored.equalsIgnoreCase(authCode));

        boolean searchGlobally = (hasAuthCode && !isAuthCodeIgnoredType) ? cdrConfigService.areAuthCodesGlobal()
                : cdrConfigService.areExtensionsGlobal();

        // --- 2. Check Pre-fetched Historical Timelines ---
        if (historicalData != null && callTimestamp != null) {
            String employeeKey = (hasAuthCode && !isAuthCodeIgnoredType) ? authCode : cleanedExtension;
            HistoricalTimeline<Employee> timeline = (hasAuthCode && !isAuthCodeIgnoredType)
                    ? historicalData.getEmployeeTimelinesByAuthCode().get(authCode)
                    : historicalData.getEmployeeTimelinesByExtension().get(cleanedExtension);

            if (timeline != null) {
                Optional<Employee> matched = timeline.findMatch(callTimestamp);
                if (matched.isPresent()) {
                    log.debug("Found employee via pre-fetched historical timeline: {}", matched.get().getId());
                    return matched;
                }

                // If we have a timeline but no match for this timestamp, legacy Step 6 says
                // block auto-creation.
                // We return empty to indicate no employee, and the caller
                // (CdrEnrichmentService)
                // or CdrProcessorService should handle the 'isMarkedForQuarantine' logic.
                log.debug("Historical group exists for {}, but no slice matches timestamp {}. Blocking.", employeeKey,
                        callTimestamp);
                return Optional.empty();
            }

            // If no pre-fetched timeline, but the extension is known to HAVE history,
            // block.
            if (hasExtension && historicalData.hasHistoryForExtension(cleanedExtension)) {
                log.debug("Extension {} has history but no matching slice for {}. Blocking auto-creation.",
                        cleanedExtension, callTimestamp);
                return Optional.empty();
            }
        }

        // --- 3. Primary Query Construction (Fallback for non-batch or first-time) ---
        StringBuilder queryStr = new StringBuilder("SELECT e.* FROM employee e ");
        queryStr.append(" LEFT JOIN communication_location cl ON e.communication_location_id = cl.id ");
        queryStr.append(" WHERE 1=1 "); // Removed e.active = true

        // Decide whether to query by AuthCode OR Extension primarily
        if (hasAuthCode && !isAuthCodeIgnoredType) {
            queryStr.append(" AND e.auth_code = :authCode ");
        } else if (hasExtension) {
            queryStr.append(" AND e.extension = :extension ");
        } else {
            log.debug("No valid extension or auth code provided for employee lookup.");
            return Optional.empty();
        }

        // Apply Location/Global constraints
        if (!searchGlobally && commLocationIdContext != null) {
            queryStr.append(" AND e.communication_location_id = :commLocationIdContext ");
        }
        // Removed cl.active requirement if we are following "active field doesn't match
        // old system"
        // But usually CommunicationLocation activity might still matter? The user said
        // "active field for employee and ranges".
        // I'll keep cl.active for now unless it's strictly forbidden too.
        // Actuallly, "ranges" also should not use active.
        queryStr.append(" AND (cl.id IS NULL OR cl.active = true) ");

        // Ordering: If we have a timestamp, we should find the version that was active
        // then.
        // If not in a timeline context (e.g. ad-hoc lookup), we find the 'latest' one.
        if (callTimestamp != null) {
            queryStr.append(" AND e.history_since <= :callTimestamp ");
            queryStr.append(" ORDER BY e.history_since DESC LIMIT 1");
        } else {
            queryStr.append(" ORDER BY e.history_since DESC LIMIT 1");
        }

        jakarta.persistence.Query nativeQuery = entityManager.createNativeQuery(queryStr.toString(), Employee.class);

        // Parameters
        if (hasAuthCode && !isAuthCodeIgnoredType) {
            nativeQuery.setParameter("authCode", authCode);
        } else if (hasExtension) {
            nativeQuery.setParameter("extension", cleanedExtension);
        }

        if (callTimestamp != null) {
            nativeQuery.setParameter("callTimestamp", callTimestamp);
        }

        if (commLocationIdContext != null) {
            nativeQuery.setParameter("commLocationIdContext", commLocationIdContext);
        }

        // --- 4. Execution & Fallback Logic ---
        try {
            Employee employee = (Employee) nativeQuery.getSingleResult();
            log.debug("Found employee by primary query: {}", employee.getId());
            return Optional.of(employee);
        } catch (jakarta.persistence.NoResultException e) {
            // --- 3.5 HISTORY CHECK: If extension or auth code has a history group,
            // but no version was found for this timestamp (or the primary query failed),
            // and we are NOT in range lookup yet, we should check if we should block
            // auto-creation.
            if (historicalData != null) {
                boolean extHasHistory = historicalData.getEmployeeTimelinesByExtension().containsKey(cleanedExtension);
                boolean authHasHistory = hasAuthCode
                        && historicalData.getEmployeeTimelinesByAuthCode().containsKey(authCode);

                if (extHasHistory || authHasHistory) {
                    log.debug(
                            "Historical data exists for identifier but no match for timestamp {}. Blocking auto-creation.",
                            callTimestamp);
                    // We return an empty optional here, which will lead to the call being processed
                    // as unknown
                    // unless it matches a range. But wait, if history exists for the EXTENSION
                    // itself,
                    // we should NOT check ranges or auto-create.
                    return Optional.empty();
                }
            }

            // --- NEW FALLBACK: If we searched by AuthCode and failed, try searching by
            // Extension now ---
            if (hasAuthCode && !isAuthCodeIgnoredType && hasExtension) {
                log.debug("Auth code lookup failed, falling back to extension lookup for: {}", cleanedExtension);

                StringBuilder fallbackSb = new StringBuilder("SELECT e.* FROM employee e ");
                fallbackSb.append(" LEFT JOIN communication_location cl ON e.communication_location_id = cl.id ");
                fallbackSb.append(" WHERE e.extension = :extension ");
                fallbackSb.append(" AND (cl.id IS NULL OR cl.active = true) ");

                if (commLocationIdContext != null) {
                    fallbackSb.append(" AND e.communication_location_id = :commLocationIdContext ");
                }

                if (callTimestamp != null) {
                    fallbackSb.append(" AND e.history_since <= :callTimestamp ");
                }
                fallbackSb.append(" ORDER BY e.history_since DESC LIMIT 1");

                jakarta.persistence.Query fallbackQuery = entityManager.createNativeQuery(fallbackSb.toString(),
                        Employee.class);
                fallbackQuery.setParameter("extension", cleanedExtension);

                if (callTimestamp != null) {
                    fallbackQuery.setParameter("callTimestamp", callTimestamp);
                }

                if (commLocationIdContext != null) {
                    fallbackQuery.setParameter("commLocationIdContext", commLocationIdContext);
                }

                try {
                    Employee fallbackEmployee = (Employee) fallbackQuery.getSingleResult();
                    log.debug("Found employee by fallback extension: {}", fallbackEmployee.getId());
                    return Optional.of(fallbackEmployee);
                } catch (jakarta.persistence.NoResultException ex2) {
                    log.trace("Fallback extension lookup returned no result.");
                }
            }

            // --- 4. Range Lookup (Conceptual Employee) ---
            boolean phpExtensionValida = hasExtension &&
                    (!cleanedExtension.startsWith("0") || cleanedExtension.equals("0")) &&
                    cleanedExtension.matches("^[0-9#*+]+$");

            if (phpExtensionValida) {
                Optional<Employee> conceptualEmployeeOpt = findEmployeeByExtensionRange(cleanedExtension,
                        commLocationIdContext, extensionRanges, callTimestamp, historicalData);
                if (conceptualEmployeeOpt.isPresent()) {
                    Employee conceptualEmployee = conceptualEmployeeOpt.get();
                    if (conceptualEmployee.getId() == null
                            && cdrConfigService.createEmployeesAutomaticallyFromRange()) {
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
     * Fetches the ExtensionLimits for all active CommunicationLocations from the
     * database in a single, efficient operation.
     * This method performs bulk lookups and processes the data in memory to avoid
     * N+1 query issues.
     * It bypasses the internal cache.
     *
     * @return A map where the key is the CommunicationLocation ID and the value is
     *         its calculated ExtensionLimits.
     */
    @SuppressWarnings("unchecked")
    @Transactional(readOnly = true)
    public Map<Long, ExtensionLimits> getExtensionLimits() {
        log.debug("Fetching all extension limits for all active Communication Locations using bulk operations.");

        // 1. Initialize a result map with default limits for all active locations.
        // This ensures every location has an entry, even if it has no employees or
        // ranges.
        String commLocationQuery = "SELECT cl FROM CommunicationLocation cl WHERE cl.active = true";
        List<CommunicationLocation> allCommLocations = entityManager
                .createQuery(commLocationQuery, CommunicationLocation.class).getResultList();
        Map<Long, ExtensionLimits> resultMap = allCommLocations.stream()
                .collect(Collectors.toMap(CommunicationLocation::getId, id -> new ExtensionLimits()));

        if (allCommLocations.isEmpty()) {
            log.debug("No active Communication Locations found to fetch extension limits for.");
            return Collections.emptyMap();
        }

        String maxAllowedLenStr = String.valueOf(CdrConfigService.MAX_EXTENSION_LENGTH_FOR_INTERNAL_CHECK);
        int maxStandardExtLength = maxAllowedLenStr.length() - 1;
        if (maxStandardExtLength < 1)
            maxStandardExtLength = 1;

        // 2. Bulk fetch Employee extension lengths
        String empLenQueryStr = "SELECT e.communication_location_id as comm_id, " +
                "  CAST(MIN(LENGTH(e.extension)) AS INTEGER) as min_len, " +
                "  CAST(MAX(LENGTH(e.extension)) AS INTEGER) as max_len " +
                "FROM employee e " +
                "JOIN communication_location cl ON e.communication_location_id = cl.id " +
                "WHERE cl.active = true " + // Removed e.active = true
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
                "WHERE cl.active = true " + // Removed er.active = true
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
                "WHERE cl.active = true " + // Removed e.active = true
                "  AND e.extension NOT LIKE '%-%' " +
                "  AND (LENGTH(e.extension) >= :maxExtStandardLenForFullList OR e.extension LIKE '0%' OR e.extension LIKE '*%' OR e.extension LIKE '#%')";
        Query specialExtQuery = entityManager.createNativeQuery(specialExtQueryStr, Tuple.class);
        specialExtQuery.setParameter("maxExtStandardLenForFullList", maxAllowedLenStr.length());
        List<Tuple> specialExtResults = specialExtQuery.getResultList();

        // Group results by comm_id in memory
        Map<Long, List<String>> specialExtensionsByCommId = specialExtResults.stream()
                .collect(Collectors.groupingBy(
                        tuple -> tuple.get("comm_id", Number.class).longValue(),
                        Collectors.mapping(tuple -> tuple.get("extension", String.class), Collectors.toList())));

        specialExtensionsByCommId.forEach((commId, extensions) -> resultMap.computeIfPresent(commId, (k, v) -> {
            v.setSpecialFullExtensions(extensions);
            return v;
        }));
        log.debug("Processed special extensions for {} locations.", specialExtensionsByCommId.size());

        // 5. Finalize the numeric min/max values from the collected lengths
        resultMap.values().forEach(ExtensionLimits::calculateFinalMinMaxValues);
        log.debug("Finished fetching and calculating all extension limits. Found limits for {} locations.",
                resultMap.size());

        return resultMap;
    }

    /**
     * Finds a conceptual employee by checking if an extension falls within a given
     * set of ranges.
     * This version receives the extension ranges as a parameter, bypassing the
     * internal cache.
     *
     * @param extension       The extension number to check.
     * @param commLocationId  The context of the current communication location,
     *                        used for global vs. local search logic.
     * @param extensionRanges A pre-fetched map of all relevant extension ranges.
     * @return An Optional containing a conceptual Employee if a match is found.
     */
    @Transactional(readOnly = true)
    public Optional<Employee> findEmployeeByExtensionRange(String extension, Long commLocationId,
            Map<Long, List<ExtensionRange>> extensionRanges, LocalDateTime callTimestamp,
            HistoricalDataContainer historicalData) {
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
                    .forEach(er -> {
                        ExtensionRange rangeToUse = er;
                        if (historicalData != null && callTimestamp != null && er.getHistoryControlId() != null) {
                            HistoricalTimeline<ExtensionRange> timeline = historicalData.getRangeTimelines()
                                    .get(er.getHistoryControlId());
                            if (timeline != null) {
                                Optional<ExtensionRange> matchedRange = timeline.findMatch(callTimestamp);
                                if (matchedRange.isPresent()) {
                                    rangeToUse = matchedRange.get();
                                    matchingRanges.add(rangeToUse);
                                }
                            } else {
                                matchingRanges.add(er);
                            }
                        } else {
                            matchingRanges.add(er);
                        }
                    });

            // Sort to prioritize the range from the current context commLocationId
            matchingRanges.sort(Comparator
                    .comparing((ExtensionRange er) -> !Objects.equals(er.getCommLocationId(), commLocationId))
                    .thenComparingLong(er -> er.getRangeEnd() - er.getRangeStart()) // then by tighter range
                    .thenComparing(ExtensionRange::getCreatedDate, Comparator.nullsLast(Comparator.reverseOrder())));
        } else {
            // If not global, only check ranges for the specific commLocationId
            List<ExtensionRange> rangesForLocation = extensionRanges.get(commLocationId);
            if (rangesForLocation != null) {
                rangesForLocation.stream()
                        .filter(er -> extNum >= er.getRangeStart() && extNum <= er.getRangeEnd())
                        .forEach(er -> {
                            ExtensionRange rangeToUse = er;
                            if (historicalData != null && callTimestamp != null && er.getHistoryControlId() != null) {
                                HistoricalTimeline<ExtensionRange> timeline = historicalData.getRangeTimelines()
                                        .get(er.getHistoryControlId());
                                if (timeline != null) {
                                    Optional<ExtensionRange> matchedRange = timeline.findMatch(callTimestamp);
                                    if (matchedRange.isPresent()) {
                                        rangeToUse = matchedRange.get();
                                        matchingRanges.add(rangeToUse);
                                    }
                                } else {
                                    matchingRanges.add(er);
                                }
                            } else {
                                matchingRanges.add(er);
                            }
                        });
                // The list is already sorted by tightness/date from the fetch query
            }
        }

        if (!matchingRanges.isEmpty()) {
            ExtensionRange bestMatch = matchingRanges.get(0);
            log.debug("Extension {} matched range (historically): {}", extension, bestMatch.getId());
            Employee conceptualEmployee = createEmployeeFromRange(
                    extension,
                    bestMatch.getSubdivisionId(),
                    bestMatch.getCommLocationId(),
                    bestMatch.getPrefix()); // Fixed from getNamePrefix()

            if (bestMatch.getCommLocationId() != null) {
                CommunicationLocation cl = entityManager.find(CommunicationLocation.class,
                        bestMatch.getCommLocationId());
                conceptualEmployee.setCommunicationLocation(cl);
            }
            return Optional.of(conceptualEmployee);
        }

        return Optional.empty();
    }

    /**
     * Fetches all active extension ranges from the database, organized by
     * CommunicationLocation ID.
     *
     * @return A map where the key is the CommunicationLocation ID and the value is
     *         a list of its ExtensionRanges.
     */
    @SuppressWarnings("unchecked")
    @Transactional(readOnly = true)
    public Map<Long, List<ExtensionRange>> getExtensionRanges() {
        log.debug("Fetching all active extension ranges from the database (cache bypassed).");

        String queryStr = "SELECT er.* FROM extension_range er " +
                "JOIN communication_location cl ON er.comm_location_id = cl.id " +
                "WHERE cl.active = true " + // Removed er.active = true
                // PHP: ORDER BY RANGOEXT_HISTODESDE DESC, RANGOEXT_DESDE DESC, RANGOEXT_HASTA
                // ASC
                // Historical part is omitted for now. Sorting by range size and creation date
                // is a good practice.
                "ORDER BY (er.range_end - er.range_start) ASC, er.created_date DESC";

        Query nativeQuery = entityManager.createNativeQuery(queryStr, ExtensionRange.class);
        List<ExtensionRange> allRanges = nativeQuery.getResultList();

        // Group the results by CommunicationLocation ID
        return allRanges.stream()
                .collect(Collectors.groupingBy(ExtensionRange::getCommLocationId));
    }
}