// File: com/infomedia/abacox/telephonypricing/component/cdrprocessing/EmployeeLookupService.java
package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import com.infomedia.abacox.telephonypricing.db.entity.CommunicationLocation;
import com.infomedia.abacox.telephonypricing.db.entity.Employee;
import com.infomedia.abacox.telephonypricing.db.entity.ExtensionRange;
import com.infomedia.abacox.telephonypricing.db.entity.HistoricalEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Log4j2
@RequiredArgsConstructor
public class EmployeeLookupService {

    @PersistenceContext
    private EntityManager entityManager;
    private final CdrConfigService cdrConfigService;

    @FunctionalInterface
    private interface HistorySliceConsumer<T> {
        void accept(T entity, long fdesde, long fhasta);
    }

    @SuppressWarnings("unchecked")
    public HistoricalDataContainer prefetchHistoricalData(Set<String> extensions, Set<String> authCodes) {
        log.debug("Pre-fetching historical data for {} extensions and {} auth codes", extensions.size(), authCodes.size());

        HistoricalDataContainer container = new HistoricalDataContainer();
        boolean isGlobalExt = cdrConfigService.areExtensionsGlobal();
        boolean isGlobalAuth = cdrConfigService.areAuthCodesGlobal();

        Set<String> cleanedExtensions = extensions.stream()
                .filter(Objects::nonNull)
                .map(CdrUtil::cleanExtension)
                .collect(Collectors.toSet());

        Set<String> validAuthCodes = authCodes.stream()
                .filter(Objects::nonNull)
                .filter(ac -> !ac.trim().isEmpty())
                .collect(Collectors.toSet());

        // --- 1. Fetch Employees ---
        if (!cleanedExtensions.isEmpty() || !validAuthCodes.isEmpty()) {
            String fetchAllQuery = "SELECT e.* FROM employee e WHERE e.extension IN (:extensions) OR e.auth_code IN (:authCodes) ORDER BY e.history_since DESC";
            jakarta.persistence.Query fetchQuery = entityManager.createNativeQuery(fetchAllQuery, Employee.class);
            fetchQuery.setParameter("extensions", cleanedExtensions.isEmpty() ? Collections.singleton("-1") : cleanedExtensions);
            fetchQuery.setParameter("authCodes", validAuthCodes.isEmpty() ? Collections.singleton("-1") : validAuthCodes);

            List<Employee> allVersions = fetchQuery.getResultList();

            processHistorySlices(allVersions, (emp, fdesde, fhasta) -> {
                if (emp.getExtension() != null && !emp.getExtension().isEmpty()) {
                    String ext = CdrUtil.cleanExtension(emp.getExtension());
                    container.addEmployeeExtensionSlice(ext, emp, fdesde, fhasta, isGlobalExt);
                }
                if (emp.getAuthCode() != null && !emp.getAuthCode().isEmpty()) {
                    container.addEmployeeAuthCodeSlice(emp.getAuthCode(), emp, fdesde, fhasta, isGlobalAuth);
                }
            });
        }

        // --- 2. Fetch Extension Ranges ---
        String fetchRangesQuery = "SELECT er.* FROM extension_range er ORDER BY er.history_since DESC";
        List<ExtensionRange> allRanges = entityManager.createNativeQuery(fetchRangesQuery, ExtensionRange.class).getResultList();

        processHistorySlices(allRanges, (range, fdesde, fhasta) -> {
            container.addRangeSlice(range.getCommLocationId(), range, fdesde, fhasta);
        });

        return container;
    }

    /**
     * Calculates the fhasta (end date) for historical groups just like PHP's Obtener_HistoricoHasta_Listado
     */
    private <T extends HistoricalEntity> void processHistorySlices(List<T> entities, HistorySliceConsumer<T> consumer) {
        // Group by history control id
        Map<Long, List<T>> grouped = entities.stream()
                .filter(e -> e.getHistoryControlId() != null)
                .collect(Collectors.groupingBy(HistoricalEntity::getHistoryControlId));

        // Process entities WITH history control ID
        for (List<T> group : grouped.values()) {
            group.sort(Comparator.comparing(HistoricalEntity::getHistorySince, Comparator.nullsLast(Comparator.reverseOrder())));
            long nextFdesde = -1;

            for (T entity : group) {
                long fdesde = entity.getHistorySince() != null ? entity.getHistorySince().toEpochSecond(ZoneOffset.UTC) : 0;
                long fhasta = -1; // -1 means open/no limit

                if (nextFdesde != -1) {
                    fhasta = nextFdesde - 1;
                }

                consumer.accept(entity, fdesde, fhasta);
                nextFdesde = fdesde;
            }
        }

        // Process entities WITHOUT history control ID (Standalone records)
        entities.stream()
                .filter(e -> e.getHistoryControlId() == null)
                .forEach(e -> {
                    long fdesde = e.getHistorySince() != null ? e.getHistorySince().toEpochSecond(ZoneOffset.UTC) : 0;
                    consumer.accept(e, fdesde, -1L);
                });
    }

    @Transactional
    public Optional<Employee> findEmployeeByExtensionOrAuthCode(String extension, String authCode,
            Long commLocationIdContext, List<String> ignoredAuthCodeDescriptions,
            Map<Long, List<ExtensionRange>> fallbackExtensionRanges, LocalDateTime callTimestamp,
            HistoricalDataContainer historicalData) {

        // --- 1. Preparation ---
        boolean hasAuthCode = authCode != null && !authCode.isEmpty();
        String cleanedExtension = (extension != null) ? CdrUtil.cleanPhoneNumber(extension, null, false).getCleanedNumber() : null;
        if (cleanedExtension != null && cleanedExtension.startsWith("+")) {
            cleanedExtension = cleanedExtension.substring(1);
        }
        boolean hasExtension = cleanedExtension != null && !cleanedExtension.isEmpty();

        boolean isAuthCodeIgnoredType = hasAuthCode && ignoredAuthCodeDescriptions.stream().anyMatch(ignored -> ignored.equalsIgnoreCase(authCode));
        String validAuthCode = (hasAuthCode && !isAuthCodeIgnoredType) ? authCode : null;

        long callTimestampEpoch = callTimestamp != null ? callTimestamp.toEpochSecond(ZoneOffset.UTC) : 0;

        // --- 2. Lazy Load Historical Data (For Synchronous Processing) ---
        if (historicalData == null) {
            Set<String> extsToFetch = hasExtension ? Collections.singleton(cleanedExtension) : Collections.emptySet();
            Set<String> authsToFetch = validAuthCode != null ? Collections.singleton(validAuthCode) : Collections.emptySet();
            historicalData = prefetchHistoricalData(extsToFetch, authsToFetch);
        }

        // --- 3. Lookup Auth Code ---
        if (validAuthCode != null) {
            HistoricalDataContainer.ResolvedTimeline authTimeline = historicalData.getAuthCodeTimelines().get(validAuthCode);
            if (authTimeline != null) {
                Optional<Employee> match = authTimeline.findMatch(callTimestampEpoch, commLocationIdContext);
                if (match.isPresent()) {
                    log.debug("Found employee via AuthCode timeline: {}", match.get().getId());
                    return match;
                }
            }
        }

        // --- 4. Lookup Extension Fallback ---
        if (hasExtension) {
            HistoricalDataContainer.ResolvedTimeline extTimeline = historicalData.getExtensionTimelines().get(cleanedExtension);
            if (extTimeline != null) {
                Optional<Employee> match = extTimeline.findMatch(callTimestampEpoch, commLocationIdContext);
                if (match.isPresent()) {
                    log.debug("Found employee via Extension timeline: {}", match.get().getId());
                    return match;
                }
            }
        }

        // --- 5. Range Lookup (Conceptual Employee) ---
        boolean phpExtensionValida = hasExtension && (!cleanedExtension.startsWith("0") || cleanedExtension.equals("0")) && cleanedExtension.matches("^[0-9#*+]+$");

        if (phpExtensionValida) {
            Optional<Employee> conceptualEmployeeOpt = findEmployeeByExtensionRange(cleanedExtension, commLocationIdContext, fallbackExtensionRanges, callTimestamp, historicalData);
            if (conceptualEmployeeOpt.isPresent()) {
                Employee conceptualEmployee = conceptualEmployeeOpt.get();
                if (conceptualEmployee.getId() == null && cdrConfigService.createEmployeesAutomaticallyFromRange()) {
                    log.debug("Persisting new employee for extension {} from range, CommLocation ID: {}", conceptualEmployee.getExtension(), conceptualEmployee.getCommunicationLocationId());
                    entityManager.persist(conceptualEmployee);
                    return Optional.of(conceptualEmployee);
                } else if (conceptualEmployee.getId() != null) {
                    return Optional.of(conceptualEmployee);
                }
            }
        }

        return Optional.empty();
    }

    @Transactional(readOnly = true)
    public Optional<Employee> findEmployeeByExtensionRange(String extension, Long commLocationId,
            Map<Long, List<ExtensionRange>> fallbackExtensionRanges, LocalDateTime callTimestamp,
            HistoricalDataContainer historicalData) {
            
        if (extension == null || !extension.matches("\\d+")) {
            return Optional.empty();
        }
        
        long extNum;
        try {
            extNum = Long.parseLong(extension);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }

        long callTimestampEpoch = callTimestamp != null ? callTimestamp.toEpochSecond(ZoneOffset.UTC) : 0;
        boolean searchRangesGlobally = cdrConfigService.areExtensionsGlobal();
        
        // Lazy load for sync processing
        if (historicalData == null) {
            historicalData = prefetchHistoricalData(Collections.emptySet(), Collections.emptySet());
        }

        List<HistoricalDataContainer.RangeSlice> matchingSlices = new ArrayList<>();

        if (searchRangesGlobally) {
            // Search across all plants
            for (List<HistoricalDataContainer.RangeSlice> slices : historicalData.getRangeSlicesByCommId().values()) {
                for (HistoricalDataContainer.RangeSlice slice : slices) {
                    if (extNum >= slice.getRange().getRangeStart() && extNum <= slice.getRange().getRangeEnd()) {
                        if ((slice.getFdesde() <= 0 || slice.getFdesde() <= callTimestampEpoch) &&
                            (slice.getFhasta() <= 0 || slice.getFhasta() >= callTimestampEpoch)) {
                            matchingSlices.add(slice);
                        }
                    }
                }
            }
        } else {
            // Search strictly within the context plant
            List<HistoricalDataContainer.RangeSlice> slices = historicalData.getRangeSlicesByCommId().get(commLocationId);
            if (slices != null) {
                for (HistoricalDataContainer.RangeSlice slice : slices) {
                    if (extNum >= slice.getRange().getRangeStart() && extNum <= slice.getRange().getRangeEnd()) {
                        if ((slice.getFdesde() <= 0 || slice.getFdesde() <= callTimestampEpoch) &&
                            (slice.getFhasta() <= 0 || slice.getFhasta() >= callTimestampEpoch)) {
                            matchingSlices.add(slice);
                        }
                    }
                }
            }
        }

        if (!matchingSlices.isEmpty()) {
            // Prioritize: 1. Exact commLocation, 2. Smallest Range Size, 3. Newest
            matchingSlices.sort(Comparator
                    .comparing((HistoricalDataContainer.RangeSlice rs) -> !Objects.equals(rs.getRange().getCommLocationId(), commLocationId))
                    .thenComparingLong(rs -> rs.getRange().getRangeEnd() - rs.getRange().getRangeStart())
                    .thenComparing(rs -> rs.getRange().getCreatedDate(), Comparator.nullsLast(Comparator.reverseOrder())));

            ExtensionRange bestMatch = matchingSlices.get(0).getRange();
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

    private Employee createEmployeeFromRange(String extension, Long subdivisionId, Long commLocationId, String namePrefix) {
        Employee newEmployee = new Employee();
        newEmployee.setExtension(extension);

        String prefixToUse = (namePrefix != null && !namePrefix.isEmpty()) ? namePrefix : cdrConfigService.getEmployeeNamePrefixFromRange();

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