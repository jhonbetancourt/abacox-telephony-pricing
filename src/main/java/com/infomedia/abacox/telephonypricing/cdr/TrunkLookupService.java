// File: com/infomedia/abacox/telephonypricing/cdr/TrunkLookupService.java
package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.Trunk;
import com.infomedia.abacox.telephonypricing.entity.TrunkRate;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@Log4j2
public class TrunkLookupService {
    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(readOnly = true)
    public Optional<TrunkInfo> findTrunkByName(String trunkName, Long commLocationId) {
        // PHP: CargarTroncales and buscarTroncal
        // PHP: CELULINK_COMUBICACION_ID in (0, $comubicacion_id) ORDER BY CELULINK_COMUBICACION_ID ASC
        // This means specific comm_location_id takes precedence if 0 means "all".
        // However, the PHP query actually sorts ASC, meaning 0 (all) would be preferred if both exist.
        // Let's stick to PHP's ASC sort for CELULINK_COMUBICACION_ID (which is t.comm_location_id).
        // If 0 means "global/default" and a specific one exists, the specific one should usually override.
        // The PHP query `ORDER BY CELULINK_COMUBICACION_ID ASC` would pick 0 first if both 0 and specific ID exist.
        // This seems counter-intuitive for overrides. Let's assume the PHP meant specific overrides global.
        // So, `ORDER BY t.comm_location_id DESC` (specific first, then 0 for global).
        String queryStr = "SELECT t.* FROM trunk t " +
                "WHERE t.active = true AND UPPER(t.name) = :trunkName " + // PHP uses strtoupper for troncal_buscar
                "AND (t.comm_location_id = :commLocationId OR t.comm_location_id = 0 OR t.comm_location_id IS NULL) " + // 0 or NULL for global trunks
                "ORDER BY CASE WHEN t.comm_location_id = :commLocationId THEN 0 ELSE 1 END, t.comm_location_id DESC NULLS LAST LIMIT 1";
                // Prioritize exact commLocationId match, then specific non-zero commLocationId, then global (0 or NULL)

        jakarta.persistence.Query nativeQuery = entityManager.createNativeQuery(queryStr, Trunk.class);
        nativeQuery.setParameter("trunkName", trunkName.toUpperCase());
        nativeQuery.setParameter("commLocationId", commLocationId);

        try {
            Trunk trunk = (Trunk) nativeQuery.getSingleResult();
            TrunkInfo ti = new TrunkInfo();
            ti.id = trunk.getId();
            ti.description = trunk.getDescription();
            ti.operatorId = trunk.getOperatorId();
            ti.noPbxPrefix = trunk.getNoPbxPrefix();

            String ratesQueryStr = "SELECT tr.* FROM trunk_rate tr WHERE tr.active = true AND tr.trunk_id = :trunkId";
            List<TrunkRate> trunkRates = entityManager.createNativeQuery(ratesQueryStr, TrunkRate.class)
                    .setParameter("trunkId", trunk.getId())
                    .getResultList();
            ti.rates = trunkRates.stream().map(tr -> {
                TrunkRateDetails rd = new TrunkRateDetails();
                rd.operatorId = tr.getOperatorId();
                rd.telephonyTypeId = tr.getTelephonyTypeId();
                rd.rateValue = tr.getRateValue();
                rd.includesVat = tr.getIncludesVat();
                rd.seconds = tr.getSeconds();
                rd.noPbxPrefix = tr.getNoPbxPrefix();
                rd.noPrefix = tr.getNoPrefix();
                return rd;
            }).collect(Collectors.toList());

            return Optional.of(ti);
        } catch (NoResultException e) {
            return Optional.empty();
        }
    }

    @Transactional(readOnly = true)
    public Optional<TrunkRateDetails> getRateDetailsForTrunk(Long trunkId, Long telephonyTypeId, Long operatorId) {
        // PHP: if (isset($existe_troncal['operador_destino'][$operador_id][$tipotele_id]))
        // PHP: else { $operador_troncal = 0; ... if (isset($existe_troncal['operador_destino'][0][$tipotele_id])) }
        // This means it first tries the specific operator, then operator 0 (all).
        String queryStr = "SELECT tr.* FROM trunk_rate tr " +
                "WHERE tr.active = true AND tr.trunk_id = :trunkId " +
                "AND tr.telephony_type_id = :telephonyTypeId " +
                "AND (tr.operator_id = :operatorId OR tr.operator_id = 0 OR tr.operator_id IS NULL) " + // Check specific operator or generic (0/NULL)
                "ORDER BY CASE WHEN tr.operator_id = :operatorId THEN 0 ELSE 1 END, tr.operator_id DESC NULLS LAST LIMIT 1"; // Prefer specific operator rate

        jakarta.persistence.Query nativeQuery = entityManager.createNativeQuery(queryStr, TrunkRate.class);
        nativeQuery.setParameter("trunkId", trunkId);
        nativeQuery.setParameter("telephonyTypeId", telephonyTypeId);
        nativeQuery.setParameter("operatorId", operatorId != null ? operatorId : 0L); // Use 0 if operatorId is null

        try {
            TrunkRate tr = (TrunkRate) nativeQuery.getSingleResult();
            TrunkRateDetails rd = new TrunkRateDetails();
            rd.operatorId = tr.getOperatorId();
            rd.telephonyTypeId = tr.getTelephonyTypeId();
            rd.rateValue = tr.getRateValue();
            rd.includesVat = tr.getIncludesVat();
            rd.seconds = tr.getSeconds();
            rd.noPbxPrefix = tr.getNoPbxPrefix();
            rd.noPrefix = tr.getNoPrefix();
            return Optional.of(rd);
        } catch (NoResultException e) {
            return Optional.empty();
        }
    }
}