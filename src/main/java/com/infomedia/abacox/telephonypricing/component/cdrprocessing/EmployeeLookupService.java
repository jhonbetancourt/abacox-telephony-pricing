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

        // Step 1: Identify identifiers that HAVE history (any record with historyControlId)
        String historyCheckQuery = "SELECT DISTINCT extension, auth_code FROM employee WHERE history_control_id IS NOT NULL "
                + "AND (extension IN (:extensions) OR auth_code IN (:authCodes))";

        jakarta.persistence.Query checkQuery = entityManager.createNativeQuery(historyCheckQuery, Tuple.class);
        checkQuery.setParameter("extensions", cleanedExtensions.isEmpty() ? Collections.singleton("-1") : cleanedExtensions);
        checkQuery.setParameter("authCodes", validAuthCodes.isEmpty() ? Collections.singleton("-1") : validAuthCodes);

        List<Tuple> historyCheckResults = checkQuery.getResultList();
        Set<String> extensionsWithHistory = new HashSet<>();
        Set<String> authCodesWithHistory = new HashSet<>();

        for (Tuple t : historyCheckResults) {
            String ext = t.get(0, String.class);
            String ac = t.get(1, String.class);
            if (ext != null) extensionsWithHistory.add(ext);
            if (ac != null) authCodesWithHistory.add(ac);
        }

        HistoricalDataContainer container = new HistoricalDataContainer(extensionsWithHistory, authCodesWithHistory);

        // Step 2: Fetch all versions for these identifiers
        if (!extensionsWithHistory.isEmpty() || !authCodesWithHistory.isEmpty()) {
            String fetchAllQuery = "SELECT e.* FROM employee e WHERE e.extension IN (:extensions) OR e.auth_code IN (:authCodes) "
                    + "ORDER BY e.history_since DESC";

            jakarta.persistence.Query fetchQuery = entityManager.createNativeQuery(fetchAllQuery, Employee.class);
            fetchQuery.setParameter("extensions", extensionsWithHistory.isEmpty() ? Collections.singleton("-1") : extensionsWithHistory);
            fetchQuery.setParameter("authCodes", authCodesWithHistory.isEmpty() ? Collections.singleton("-1") : authCodesWithHistory);

            List<Employee> allVersions = fetchQuery.getResultList();

            // Group by HistoryControlId
            Map<Long, List<Employee>> versionsByGroup = allVersions.stream()
                    .filter(e -> e.getHistoryControlId() != null)
                    .collect(Collectors.groupingBy(Employee::getHistoryControlId));

            for (Map.Entry<Long, List<Employee>> entry : versionsByGroup.entrySet()) {
                HistoricalTimeline<Employee> timeline = new HistoricalTimeline<>(entry.getKey(), entry.getValue());
                
                // Map the timeline to all unique extensions/auth codes it represents to avoid duplicate timeline lists
                Set<String> uniqueExts = entry.getValue().stream().map(Employee::getExtension).filter(Objects::nonNull).collect(Collectors.toSet());
                for (String ext : uniqueExts) {
                    container.addEmployeeTimelineByExtension(ext, timeline);
                }
                
                Set<String> uniqueAuths = entry.getValue().stream().map(Employee::getAuthCode).filter(Objects::nonNull).collect(Collectors.toSet());
                for (String auth : uniqueAuths) {
                    container.addEmployeeTimelineByAuthCode(auth, timeline);
                }
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

    private Employee createEmployeeFromRange(String extension, Long subdivisionId, Long commLocationId, String namePrefix) {
        Employee newEmployee = new Employee();
        newEmployee.setExtension(extension);

        String prefixToUse = (namePrefix != null && !namePrefix.isEmpty())
                ? namePrefix
                : cdrConfigService.getEmployeeNamePrefixFromRange();

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
            List<HistoricalTimeline<Employee>> timelines = (hasAuthCode && !isAuthCodeIgnoredType)
                    ? historicalData.getEmployeeTimelinesByAuthCode().get(authCode)
                    : historicalData.getEmployeeTimelinesByExtension().get(cleanedExtension);

            if (timelines != null && !timelines.isEmpty()) {
                Employee bestMatch = null;
                Employee fallbackGlobalMatch = null;

                for (HistoricalTimeline<Employee> timeline : timelines) {
                    Optional<Employee> matchedOpt = timeline.findMatch(callTimestamp);
                    if (matchedOpt.isPresent()) {
                        Employee matchedEmp = matchedOpt.get();
                        
                        // Exact Location Match
                        if (commLocationIdContext == null || Objects.equals(matchedEmp.getCommunicationLocationId(), commLocationIdContext)) {
                            bestMatch = matchedEmp;
                            break; 
                        } else if (searchGlobally && fallbackGlobalMatch == null) {
                            // PHP: if ($retornar['id'] <= 0 && $primer_funid['id'] > 0 && $ext_globales) { $retornar = $primer_funid; }
                            fallbackGlobalMatch = matchedEmp;
                        }
                    }
                }

                if (bestMatch != null) {
                    log.debug("Found employee via pre-fetched historical timeline: {}", bestMatch.getId());
                    return Optional.of(bestMatch);
                } else if (fallbackGlobalMatch != null) {
                    log.debug("Found global fallback employee via pre-fetched historical timeline: {}", fallbackGlobalMatch.getId());
                    return Optional.of(fallbackGlobalMatch);
                }

                log.debug("Historical group exists for {}, but no slice matches timestamp {} and location context. Blocking.", employeeKey, callTimestamp);
                return Optional.empty();
            }

            // If no pre-fetched timeline, but the extension is known to HAVE history globally, block auto-creation.
            if (hasExtension && historicalData.hasHistoryForExtension(cleanedExtension)) {
                log.debug("Extension {} has history but no matching slice for {}. Blocking auto-creation.", cleanedExtension, callTimestamp);
                return Optional.empty();
            }
        }

        // --- 3. Primary Query Construction (Fallback for non-batch or first-time) ---
        StringBuilder queryStr = new StringBuilder("SELECT e.* FROM employee e ");
        queryStr.append(" LEFT JOIN communication_location cl ON e.communication_location_id = cl.id ");
        queryStr.append(" WHERE 1=1 ");

        if (hasAuthCode && !isAuthCodeIgnoredType) {
            queryStr.append(" AND e.auth_code = :authCode ");
        } else if (hasExtension) {
            queryStr.append(" AND e.extension = :extension ");
        } else {
            return Optional.empty();
        }

        if (!searchGlobally && commLocationIdContext != null) {
            queryStr.append(" AND e.communication_location_id = :commLocationIdContext ");
        }
        queryStr.append(" AND (cl.id IS NULL OR cl.active = true) ");

        if (callTimestamp != null) {
            queryStr.append(" AND e.history_since <= :callTimestamp ");
            queryStr.append(" ORDER BY e.history_since DESC LIMIT 1");
        } else {
            queryStr.append(" ORDER BY e.history_since DESC LIMIT 1");
        }

        jakarta.persistence.Query nativeQuery = entityManager.createNativeQuery(queryStr.toString(), Employee.class);

        if (hasAuthCode && !isAuthCodeIgnoredType) {
            nativeQuery.setParameter("authCode", authCode);
        } else if (hasExtension) {
            nativeQuery.setParameter("extension", cleanedExtension);
        }
        if (callTimestamp != null) {
            nativeQuery.setParameter("callTimestamp", callTimestamp);
        }
        if (!searchGlobally && commLocationIdContext != null) {
            nativeQuery.setParameter("commLocationIdContext", commLocationIdContext);
        }

        // --- 4. Execution & Fallback Logic ---
        try {
            Employee employee = (Employee) nativeQuery.getSingleResult();
            log.debug("Found employee by primary query: {}", employee.getId());
            return Optional.of(employee);
        } catch (jakarta.persistence.NoResultException e) {
            
            if (historicalData != null) {
                boolean extHasHistory = historicalData.getEmployeeTimelinesByExtension().containsKey(cleanedExtension);
                boolean authHasHistory = hasAuthCode && historicalData.getEmployeeTimelinesByAuthCode().containsKey(authCode);

                if (extHasHistory || authHasHistory) {
                    log.debug("Historical data exists for identifier but no match for timestamp {}. Blocking auto-creation.", callTimestamp);
                    return Optional.empty();
                }
            }

            // --- FALLBACK: If we searched by AuthCode and failed, try searching by Extension now ---
            if (hasAuthCode && !isAuthCodeIgnoredType && hasExtension) {
                log.debug("Auth code lookup failed, falling back to extension lookup for: {}", cleanedExtension);

                StringBuilder fallbackSb = new StringBuilder("SELECT e.* FROM employee e ");
                fallbackSb.append(" LEFT JOIN communication_location cl ON e.communication_location_id = cl.id ");
                fallbackSb.append(" WHERE e.extension = :extension ");
                fallbackSb.append(" AND (cl.id IS NULL OR cl.active = true) ");

                if (!searchGlobally && commLocationIdContext != null) {
                    fallbackSb.append(" AND e.communication_location_id = :commLocationIdContext ");
                }
                if (callTimestamp != null) {
                    fallbackSb.append(" AND e.history_since <= :callTimestamp ");
                }
                fallbackSb.append(" ORDER BY e.history_since DESC LIMIT 1");

                jakarta.persistence.Query fallbackQuery = entityManager.createNativeQuery(fallbackSb.toString(), Employee.class);
                fallbackQuery.setParameter("extension", cleanedExtension);

                if (callTimestamp != null) {
                    fallbackQuery.setParameter("callTimestamp", callTimestamp);
                }
                if (!searchGlobally && commLocationIdContext != null) {
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

            // --- 5. Range Lookup (Conceptual Employee) ---
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

    @SuppressWarnings("unchecked")
    @Transactional(readOnly = true)
    public Map<Long, ExtensionLimits> getExtensionLimits() {
        log.debug("Fetching all extension limits for all active Communication Locations using bulk operations.");

        String commLocationQuery = "SELECT cl FROM CommunicationLocation cl WHERE cl.active = true";
        List<CommunicationLocation> allCommLocations = entityManager
                .createQuery(commLocationQuery, CommunicationLocation.class).getResultList();
        Map<Long, ExtensionLimits> resultMap = allCommLocations.stream()
                .collect(Collectors.toMap(CommunicationLocation::getId, id -> new ExtensionLimits()));

        if (allCommLocations.isEmpty()) {
            return Collections.emptyMap();
        }

        String maxAllowedLenStr = String.valueOf(CdrConfigService.MAX_EXTENSION_LENGTH_FOR_INTERNAL_CHECK);
        int maxStandardExtLength = maxAllowedLenStr.length() - 1;
        if (maxStandardExtLength < 1) maxStandardExtLength = 1;

        // Bulk fetch Employee lengths
        String empLenQueryStr = "SELECT e.communication_location_id as comm_id, " +
                "  CAST(MIN(LENGTH(e.extension)) AS INTEGER) as min_len, " +
                "  CAST(MAX(LENGTH(e.extension)) AS INTEGER) as max_len " +
                "FROM employee e " +
                "JOIN communication_location cl ON e.communication_location_id = cl.id " +
                "WHERE cl.active = true " + 
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

        // Bulk fetch ExtensionRange lengths
        String rangeLenQueryStr = "SELECT er.comm_location_id as comm_id, " +
                "  CAST(MIN(LENGTH(er.range_start::text)) AS INTEGER) as min_len, " +
                "  CAST(MAX(LENGTH(er.range_end::text)) AS INTEGER) as max_len " +
                "FROM extension_range er " +
                "JOIN communication_location cl ON er.comm_location_id = cl.id " +
                "WHERE cl.active = true " + 
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

        // Bulk fetch Special Extensions
        String specialExtQueryStr = "SELECT e.communication_location_id as comm_id, e.extension " +
                "FROM employee e " +
                "JOIN communication_location cl ON e.communication_location_id = cl.id " +
                "WHERE cl.active = true " + 
                "  AND e.extension NOT LIKE '%-%' " +
                "  AND (LENGTH(e.extension) >= :maxExtStandardLenForFullList OR e.extension LIKE '0%' OR e.extension LIKE '*%' OR e.extension LIKE '#%')";
        Query specialExtQuery = entityManager.createNativeQuery(specialExtQueryStr, Tuple.class);
        specialExtQuery.setParameter("maxExtStandardLenForFullList", maxAllowedLenStr.length());
        List<Tuple> specialExtResults = specialExtQuery.getResultList();

        Map<Long, List<String>> specialExtensionsByCommId = specialExtResults.stream()
                .collect(Collectors.groupingBy(
                        tuple -> tuple.get("comm_id", Number.class).longValue(),
                        Collectors.mapping(tuple -> tuple.get("extension", String.class), Collectors.toList())));

        specialExtensionsByCommId.forEach((commId, extensions) -> resultMap.computeIfPresent(commId, (k, v) -> {
            v.setSpecialFullExtensions(extensions);
            return v;
        }));

        resultMap.values().forEach(ExtensionLimits::calculateFinalMinMaxValues);
        return resultMap;
    }

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
            extensionRanges.values().stream()
                    .flatMap(List::stream) 
                    .filter(er -> extNum >= er.getRangeStart() && extNum <= er.getRangeEnd())
                    .forEach(er -> {
                        if (historicalData != null && callTimestamp != null && er.getHistoryControlId() != null) {
                            HistoricalTimeline<ExtensionRange> timeline = historicalData.getRangeTimelines().get(er.getHistoryControlId());
                            if (timeline != null) {
                                Optional<ExtensionRange> matchedRange = timeline.findMatch(callTimestamp);
                                if (matchedRange.isPresent()) {
                                    matchingRanges.add(matchedRange.get());
                                }
                            } else {
                                matchingRanges.add(er);
                            }
                        } else {
                            matchingRanges.add(er);
                        }
                    });

            matchingRanges.sort(Comparator
                    .comparing((ExtensionRange er) -> !Objects.equals(er.getCommLocationId(), commLocationId))
                    .thenComparingLong(er -> er.getRangeEnd() - er.getRangeStart()) 
                    .thenComparing(ExtensionRange::getCreatedDate, Comparator.nullsLast(Comparator.reverseOrder())));
        } else {
            List<ExtensionRange> rangesForLocation = extensionRanges.get(commLocationId);
            if (rangesForLocation != null) {
                rangesForLocation.stream()
                        .filter(er -> extNum >= er.getRangeStart() && extNum <= er.getRangeEnd())
                        .forEach(er -> {
                            if (historicalData != null && callTimestamp != null && er.getHistoryControlId() != null) {
                                HistoricalTimeline<ExtensionRange> timeline = historicalData.getRangeTimelines().get(er.getHistoryControlId());
                                if (timeline != null) {
                                    Optional<ExtensionRange> matchedRange = timeline.findMatch(callTimestamp);
                                    if (matchedRange.isPresent()) {
                                        // Guard: Ensure the historical slice hasn't drifted to a different plant!
                                        if (Objects.equals(matchedRange.get().getCommLocationId(), commLocationId)) {
                                            matchingRanges.add(matchedRange.get());
                                        }
                                    }
                                } else {
                                    matchingRanges.add(er);
                                }
                            } else {
                                matchingRanges.add(er);
                            }
                        });
            }
        }

        if (!matchingRanges.isEmpty()) {
            ExtensionRange bestMatch = matchingRanges.get(0);
            log.debug("Extension {} matched range (historically): {}", extension, bestMatch.getId());
            Employee conceptualEmployee = createEmployeeFromRange(
                    extension,
                    bestMatch.getSubdivisionId(),
                    bestMatch.getCommLocationId(),
                    bestMatch.getPrefix()); 

            if (bestMatch.getCommLocationId() != null) {
                CommunicationLocation cl = entityManager.find(CommunicationLocation.class, bestMatch.getCommLocationId());
                conceptualEmployee.setCommunicationLocation(cl);
            }
            return Optional.of(conceptualEmployee);
        }

        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    @Transactional(readOnly = true)
    public Map<Long, List<ExtensionRange>> getExtensionRanges() {
        log.debug("Fetching all active extension ranges from the database (cache bypassed).");

        String queryStr = "SELECT er.* FROM extension_range er " +
                "JOIN communication_location cl ON er.comm_location_id = cl.id " +
                "WHERE cl.active = true " + 
                "ORDER BY (er.range_end - er.range_start) ASC, er.created_date DESC";

        Query nativeQuery = entityManager.createNativeQuery(queryStr, ExtensionRange.class);
        List<ExtensionRange> allRanges = nativeQuery.getResultList();

        return allRanges.stream().collect(Collectors.groupingBy(ExtensionRange::getCommLocationId));
    }
}