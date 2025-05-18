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
        // PHP's CargarTroncales and buscarTroncal
        String queryStr = "SELECT t.* FROM trunk t " +
                "WHERE t.active = true AND t.name = :trunkName " +
                "AND (t.comm_location_id = :commLocationId OR t.comm_location_id = 0) " + // 0 for global trunks
                "ORDER BY t.comm_location_id DESC LIMIT 1"; // Prefer specific over global

        jakarta.persistence.Query nativeQuery = entityManager.createNativeQuery(queryStr, Trunk.class);
        nativeQuery.setParameter("trunkName", trunkName.toUpperCase()); // Assuming trunk names are case-insensitive in DB or stored uppercase
        nativeQuery.setParameter("commLocationId", commLocationId);

        try {
            Trunk trunk = (Trunk) nativeQuery.getSingleResult();
            TrunkInfo ti = new TrunkInfo();
            ti.id = trunk.getId();
            ti.description = trunk.getDescription();
            ti.operatorId = trunk.getOperatorId();
            ti.noPbxPrefix = trunk.getNoPbxPrefix();

            // Load associated rates (TARIFATRONCAL)
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
        String queryStr = "SELECT tr.* FROM trunk_rate tr " +
                "WHERE tr.active = true AND tr.trunk_id = :trunkId " +
                "AND tr.telephony_type_id = :telephonyTypeId " +
                "AND (tr.operator_id = :operatorId OR tr.operator_id = 0) " + // Check specific operator or generic (0)
                "ORDER BY tr.operator_id DESC LIMIT 1"; // Prefer specific operator rate

        jakarta.persistence.Query nativeQuery = entityManager.createNativeQuery(queryStr, TrunkRate.class);
        nativeQuery.setParameter("trunkId", trunkId);
        nativeQuery.setParameter("telephonyTypeId", telephonyTypeId);
        nativeQuery.setParameter("operatorId", operatorId);

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
