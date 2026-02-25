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
     * Determines the best CommunicationLocation for a CDR based on its identifying
     * fields.
     * This mimics parts of PHP's hc_cisco_cm.php (buscarExtensiones,
     * buscarPlantaDestino)
     * but scoped to a single client's CommunicationLocations.
     *
     * @param plantTypeId                     The plantType of the CDR source (e.g., Cisco CM 6.0)
     * @param callingPartyNumber              From CDR
     * @param callingPartyNumberPartition     From CDR
     * @param finalCalledPartyNumber          From CDR
     * @param finalCalledPartyNumberPartition From CDR
     * @param lastRedirectDn                  From CDR
     * @param lastRedirectDnPartition         From CDR
     * @param callDateTime                    For historical context evaluation
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
            commLocationOpt = findCommLocationByExtension(plantTypeId, callingPartyNumber, callingPartyNumberPartition,
                    callDateTime);
            if (commLocationOpt.isPresent()) {
                log.debug("Routed by CallingParty: {} -> CommLocation ID: {}", callingPartyNumber,
                        commLocationOpt.get().getId());
                return commLocationOpt;
            }
        }

        // Check Final Called Party
        if (finalCalledPartyNumber != null && !finalCalledPartyNumber.isEmpty()) {
            commLocationOpt = findCommLocationByExtension(plantTypeId, finalCalledPartyNumber,
                    finalCalledPartyNumberPartition, callDateTime);
            if (commLocationOpt.isPresent()) {
                log.debug("Routed by FinalCalledParty: {} -> CommLocation ID: {}", finalCalledPartyNumber,
                        commLocationOpt.get().getId());
                return commLocationOpt;
            }
        }

        // Check Last Redirect DN
        if (lastRedirectDn != null && !lastRedirectDn.isEmpty()) {
            commLocationOpt = findCommLocationByExtension(plantTypeId, lastRedirectDn, lastRedirectDnPartition,
                    callDateTime);
            if (commLocationOpt.isPresent()) {
                log.debug("Routed by LastRedirectDN: {} -> CommLocation ID: {}", lastRedirectDn,
                        commLocationOpt.get().getId());
                return commLocationOpt;
            }
        }

        // Fallback: If no specific match, and there's only ONE active
        // CommunicationLocation for this plantType, use it.
        // This mimics PHP's behavior where if a client has only one plant, CDRs might default to it.
        List<CommunicationLocation> activeCommLocationsForPlantType = findActiveCommLocationsByPlantType(plantTypeId);
        if (activeCommLocationsForPlantType.size() == 1) {
            log.debug(
                    "No specific extension match. Defaulting to the single active CommLocation ID: {} for PlantType: {}",
                    activeCommLocationsForPlantType.get(0).getId(), plantTypeId);
            return Optional.of(activeCommLocationsForPlantType.get(0));
        } else if (activeCommLocationsForPlantType.isEmpty()) {
            log.debug("No active CommunicationLocation found for PlantType ID: {}", plantTypeId);
        } else {
            log.debug(
                    "Multiple ({}) active CommunicationLocations found for PlantType ID: {} and no specific extension match. Cannot uniquely determine CommLocation.",
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
        if (cleanedExtension.startsWith("+"))
            cleanedExtension = cleanedExtension.substring(1);

        // 1. Try direct Employee lookup by extension
        StringBuilder empQueryBuilder = new StringBuilder(
                "SELECT cl.* FROM communication_location cl " +
                        "JOIN employee e ON e.communication_location_id = cl.id " +
                        "WHERE cl.active = true AND cl.plant_type_id = :plantTypeId " +
                        "AND e.extension = :extension ");
        
        if (callDateTime != null) {
            // Emulate PHP's ValidarFechasHistorico & Obtener_HistoricoHasta logic efficiently in SQL:
            // "Match where history_since <= callDate AND there is NO newer record in the same history_control_id group that is ALSO <= callDate"
            empQueryBuilder.append("AND (e.history_since IS NULL OR e.history_since <= :callDateTime) ");
            empQueryBuilder.append("AND (e.history_control_id IS NULL OR NOT EXISTS ( ");
            empQueryBuilder.append("    SELECT 1 FROM employee e2 ");
            empQueryBuilder.append("    WHERE e2.history_control_id = e.history_control_id ");
            empQueryBuilder.append("      AND e2.history_since > e.history_since ");
            empQueryBuilder.append("      AND e2.history_since <= :callDateTime ");
            empQueryBuilder.append(")) ");
        }
        empQueryBuilder.append("ORDER BY e.history_since DESC NULLS LAST LIMIT 1");

        jakarta.persistence.Query empQuery = entityManager.createNativeQuery(empQueryBuilder.toString(),
                CommunicationLocation.class);
        empQuery.setParameter("plantTypeId", plantTypeId);
        empQuery.setParameter("extension", cleanedExtension);
        if (callDateTime != null) {
            empQuery.setParameter("callDateTime", callDateTime);
        }

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
                        "WHERE cl.active = true AND cl.plant_type_id = :plantTypeId " +
                        "AND er.range_start <= :extNum AND er.range_end >= :extNum ");
        
        if (callDateTime != null) {
            // Replicate PHP historical limit validation for ranges
            rangeQueryBuilder.append("AND (er.history_since IS NULL OR er.history_since <= :callDateTime) ");
            rangeQueryBuilder.append("AND (er.history_control_id IS NULL OR NOT EXISTS ( ");
            rangeQueryBuilder.append("    SELECT 1 FROM extension_range er2 ");
            rangeQueryBuilder.append("    WHERE er2.history_control_id = er.history_control_id ");
            rangeQueryBuilder.append("      AND er2.history_since > er.history_since ");
            rangeQueryBuilder.append("      AND er2.history_since <= :callDateTime ");
            rangeQueryBuilder.append(")) ");
        }
        rangeQueryBuilder.append("ORDER BY (er.range_end - er.range_start) ASC, er.history_since DESC NULLS LAST LIMIT 1");
        
        jakarta.persistence.Query rangeQuery = entityManager.createNativeQuery(rangeQueryBuilder.toString(),
                CommunicationLocation.class);
        rangeQuery.setParameter("plantTypeId", plantTypeId);
        rangeQuery.setParameter("extNum", extNum);
        if (callDateTime != null) {
            rangeQuery.setParameter("callDateTime", callDateTime);
        }

        try {
            CommunicationLocation cl = (CommunicationLocation) rangeQuery.getSingleResult();
            log.debug("Found CommLocation ID {} via ExtensionRange for extension {}", cl.getId(), cleanedExtension);
            return Optional.of(cl);
        } catch (NoResultException e) {
            log.debug("Extension {} not found in direct Employee lookup or ExtensionRange for plantType {}",
                    cleanedExtension, plantTypeId);
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