package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import com.infomedia.abacox.telephonypricing.db.entity.CommunicationLocation;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@Log4j2
public class CommunicationLocationLookupService {

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(readOnly = true)
    public Optional<CommunicationLocation> findById(Long commLocationId) {
        try {
            CommunicationLocation cl = entityManager.find(CommunicationLocation.class, commLocationId);
            return Optional.ofNullable(cl);
        } catch (NoResultException e) {
            return Optional.empty();
        }
    }


    /**
     * Determines the best CommunicationLocation for a CDR based on its identifying fields.
     * This mimics parts of PHP's hc_cisco_cm.php (buscarExtensiones, buscarPlantaDestino)
     * but scoped to a single client's CommunicationLocations.
     *
     * @param plantTypeId The plantType of the CDR source (e.g., Cisco CM 6.0)
     * @param callingPartyNumber From CDR
     * @param callingPartyNumberPartition From CDR
     * @param finalCalledPartyNumber From CDR
     * @param finalCalledPartyNumberPartition From CDR
     * @param lastRedirectDn From CDR
     * @param lastRedirectDnPartition From CDR
     * @param callDateTime For historical context (currently simplified)
     * @return Optional<CommunicationLocation>
     */
    @Transactional(readOnly = true)
    public Optional<CommunicationLocation> findBestCommunicationLocation(
            Long plantTypeId,
            String callingPartyNumber, String callingPartyNumberPartition,
            String finalCalledPartyNumber, String finalCalledPartyNumberPartition,
            String lastRedirectDn, String lastRedirectDnPartition,
            LocalDateTime callDateTime) {

        log.debug("Finding best CommLocation for PlantType: {}, Calling: {}[{}], Final: {}[{}], Redirect: {}[{}]",
                plantTypeId, callingPartyNumber, callingPartyNumberPartition,
                finalCalledPartyNumber, finalCalledPartyNumberPartition,
                lastRedirectDn, lastRedirectDnPartition);

        // Priority:
        // 1. Calling Party (if it's a known extension in one of our CommLocations)
        // 2. Final Called Party (if it's a known extension)
        // 3. Last Redirect DN (if it's a known extension)

        Optional<CommunicationLocation> commLocationOpt;

        // Check Calling Party
        if (callingPartyNumber != null && !callingPartyNumber.isEmpty()) {
            commLocationOpt = findCommLocationByExtension(plantTypeId, callingPartyNumber, callingPartyNumberPartition, callDateTime);
            if (commLocationOpt.isPresent()) {
                log.debug("Routed by CallingParty: {} -> CommLocation ID: {}", callingPartyNumber, commLocationOpt.get().getId());
                return commLocationOpt;
            }
        }

        // Check Final Called Party
        if (finalCalledPartyNumber != null && !finalCalledPartyNumber.isEmpty()) {
            commLocationOpt = findCommLocationByExtension(plantTypeId, finalCalledPartyNumber, finalCalledPartyNumberPartition, callDateTime);
            if (commLocationOpt.isPresent()) {
                log.debug("Routed by FinalCalledParty: {} -> CommLocation ID: {}", finalCalledPartyNumber, commLocationOpt.get().getId());
                return commLocationOpt;
            }
        }

        // Check Last Redirect DN
        if (lastRedirectDn != null && !lastRedirectDn.isEmpty()) {
            commLocationOpt = findCommLocationByExtension(plantTypeId, lastRedirectDn, lastRedirectDnPartition, callDateTime);
            if (commLocationOpt.isPresent()) {
                log.debug("Routed by LastRedirectDN: {} -> CommLocation ID: {}", lastRedirectDn, commLocationOpt.get().getId());
                return commLocationOpt;
            }
        }

        // Fallback: If no specific match, and there's only ONE active CommunicationLocation for this plantType, use it.
        // This mimics PHP's behavior where if a client has only one plant, CDRs might default to it.
        List<CommunicationLocation> activeCommLocationsForPlantType = findActiveCommLocationsByPlantType(plantTypeId);
        if (activeCommLocationsForPlantType.size() == 1) {
            log.debug("No specific extension match. Defaulting to the single active CommLocation ID: {} for PlantType: {}",
                    activeCommLocationsForPlantType.get(0).getId(), plantTypeId);
            return Optional.of(activeCommLocationsForPlantType.get(0));
        } else if (activeCommLocationsForPlantType.isEmpty()) {
            log.debug("No active CommunicationLocation found for PlantType ID: {}", plantTypeId);
        } else {
            log.debug("Multiple ({}) active CommunicationLocations found for PlantType ID: {} and no specific extension match. Cannot uniquely determine CommLocation.",
                    activeCommLocationsForPlantType.size(), plantTypeId);
        }

        return Optional.empty();
    }

    private Optional<CommunicationLocation> findCommLocationByExtension(
            Long plantTypeId, String extensionNumber, String partitionName, LocalDateTime callDateTime) {

        if (extensionNumber == null || extensionNumber.isEmpty()) {
            return Optional.empty();
        }
        String cleanedExtension = CdrUtil.cleanPhoneNumber(extensionNumber, null, false).getCleanedNumber();
        if (cleanedExtension.startsWith("+")) cleanedExtension = cleanedExtension.substring(1);


        // 1. Try direct Employee lookup by extension
        // PHP: $query = "SELECT ... FROM funcionario JOIN comubicacion ... WHERE FUNCIONARIO_EXTENSION = :ext"
        // We need to consider the partition implicitly if it's part of the extension string in some systems,
        // or if specific logic for partition mapping is added. Cisco CDRs provide partition separately.
        // For now, we assume extension is unique enough or partition is handled by plantType context.

        StringBuilder empQueryBuilder = new StringBuilder(
                "SELECT cl.* FROM communication_location cl " +
                        "JOIN employee e ON e.communication_location_id = cl.id " +
                        "WHERE cl.active = true AND e.active = true AND cl.plant_type_id = :plantTypeId " +
                        "AND e.extension = :extension "
        );
        // In Cisco, partition is important. If partitionName is provided and not "NN-VALIDA" (PHP's default for no partition)
        // we might need a more complex query if employees can have same extension in different partitions *within the same commLocation*.
        // However, usually an extension is tied to one employee in one commLocation.
        // The PHP logic for `cm_procesar` doesn't seem to use partition directly in `buscarExtensiones` SQL,
        // but rather uses it as a key to group extensions before calling `buscarExtensiones`.
        // Here, we are looking across all commLocations of the client for this plantType.
        empQueryBuilder.append("ORDER BY e.created_date DESC LIMIT 1"); // Simplified: take most recent if multiple

        jakarta.persistence.Query empQuery = entityManager.createNativeQuery(empQueryBuilder.toString(), CommunicationLocation.class);
        empQuery.setParameter("plantTypeId", plantTypeId);
        empQuery.setParameter("extension", cleanedExtension);

        try {
            CommunicationLocation cl = (CommunicationLocation) empQuery.getSingleResult();
            log.debug("Found CommLocation ID {} via Employee extension {}", cl.getId(), cleanedExtension);
            return Optional.of(cl);
        } catch (NoResultException e) {
            // Not found via direct employee extension, try range
        }

        // 2. Try ExtensionRange lookup
        if (!cleanedExtension.matches("\\d+")) { // Ranges are numeric
            return Optional.empty();
        }
        long extNum;
        try {
            extNum = Long.parseLong(cleanedExtension);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }

        StringBuilder rangeQueryBuilder = new StringBuilder(
                "SELECT cl.* FROM communication_location cl " +
                        "JOIN extension_range er ON er.comm_location_id = cl.id " +
                        "WHERE cl.active = true AND er.active = true AND cl.plant_type_id = :plantTypeId " +
                        "AND er.range_start <= :extNum AND er.range_end >= :extNum " +
                        "ORDER BY (er.range_end - er.range_start) ASC, er.created_date DESC LIMIT 1" // Prefer tighter range
        );
        jakarta.persistence.Query rangeQuery = entityManager.createNativeQuery(rangeQueryBuilder.toString(), CommunicationLocation.class);
        rangeQuery.setParameter("plantTypeId", plantTypeId);
        rangeQuery.setParameter("extNum", extNum);

        try {
            CommunicationLocation cl = (CommunicationLocation) rangeQuery.getSingleResult();
            log.debug("Found CommLocation ID {} via ExtensionRange for extension {}", cl.getId(), cleanedExtension);
            return Optional.of(cl);
        } catch (NoResultException e) {
            log.debug("Extension {} not found in direct Employee lookup or ExtensionRange for plantType {}", cleanedExtension, plantTypeId);
            return Optional.empty();
        }
    }

    @Transactional(readOnly = true)
    public List<CommunicationLocation> findActiveCommLocationsByPlantType(Long plantTypeId) {
        String queryStr = "SELECT cl.* FROM communication_location cl " +
                "WHERE cl.active = true AND cl.plant_type_id = :plantTypeId";
        return entityManager.createNativeQuery(queryStr, CommunicationLocation.class)
                .setParameter("plantTypeId", plantTypeId)
                .getResultList();
    }
}
