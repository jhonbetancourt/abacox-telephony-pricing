// File: com/infomedia/abacox/telephonypricing/cdr/OperatorLookupService.java
package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import com.infomedia.abacox.telephonypricing.entity.Indicator;
import com.infomedia.abacox.telephonypricing.entity.Operator;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@Log4j2
public class OperatorLookupService {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Finds an operator for an incoming cellular call based on the destination indicator's bands.
     * This logic is now enhanced to prioritize the operator listed directly on the Indicator entity,
     * but only if that operator is also found as a valid candidate through the band/prefix lookup.
     * If the indicator's primary operator is not a valid candidate, it falls back to the first
     * available candidate from the bands.
     *
     * @param destinationIndicatorId The indicator ID of the (incoming) cellular number.
     * @return Optional<OperatorInfo>
     */
    @Transactional(readOnly = true)
    public Optional<OperatorInfo> findOperatorForIncomingCellularByIndicatorBands(Long destinationIndicatorId) {
        if (destinationIndicatorId == null || destinationIndicatorId <= 0) {
            return Optional.empty();
        }

        // Step 1: Fetch the indicator to get its primary operator_id
        Indicator indicator = entityManager.find(Indicator.class, destinationIndicatorId);
        Long primaryOperatorId = (indicator != null) ? indicator.getOperatorId() : null;
        log.debug("Indicator {} has primary operator ID: {}", destinationIndicatorId, primaryOperatorId);

        // Step 2: Get ALL candidate operators from bands (query without LIMIT 1)
        String queryStr = "SELECT DISTINCT p.operator_id as operator_id, op.name as operator_name " +
                          "FROM band b " +
                          "JOIN band_indicator bi ON b.id = bi.band_id " +
                          "JOIN prefix p ON b.prefix_id = p.id " +
                          "JOIN operator op ON p.operator_id = op.id " +
                          "WHERE bi.indicator_id = :destinationIndicatorId " +
                          "AND b.active = true AND p.active = true AND op.active = true " +
                          "AND p.telephony_type_id = :cellularTelephonyTypeId";

        jakarta.persistence.Query nativeQuery = entityManager.createNativeQuery(queryStr, Tuple.class);
        nativeQuery.setParameter("destinationIndicatorId", destinationIndicatorId);
        nativeQuery.setParameter("cellularTelephonyTypeId", TelephonyTypeEnum.CELLULAR.getValue());

        List<Tuple> results = nativeQuery.getResultList();
        if (results.isEmpty()) {
            log.debug("No candidate operators found via bands for indicator {}", destinationIndicatorId);
            return Optional.empty();
        }

        List<OperatorInfo> candidateOperators = results.stream()
            .map(row -> new OperatorInfo(
                row.get("operator_id", Number.class).longValue(),
                row.get("operator_name", String.class)
            ))
            .collect(Collectors.toList());
        log.debug("Found {} candidate operators via bands: {}", candidateOperators.size(), candidateOperators);

        // Step 3: Prioritization Logic
        if (primaryOperatorId != null && primaryOperatorId > 0) {
            Optional<OperatorInfo> prioritizedOperator = candidateOperators.stream()
                .filter(op -> primaryOperatorId.equals(op.getId()))
                .findFirst();

            if (prioritizedOperator.isPresent()) {
                log.info("Prioritizing operator {} ('{}') for indicator {} because it's the indicator's primary operator and is a valid candidate.",
                        primaryOperatorId, prioritizedOperator.get().getName(), destinationIndicatorId);
                return prioritizedOperator;
            }
        }

        // Step 4: Fallback Logic
        // If we are here, it means either the indicator had no primary operator, or its primary operator
        // was not in the list of valid candidates from the bands. We fall back to the original behavior.
        OperatorInfo fallbackOperator = candidateOperators.get(0);
        log.info("Indicator's primary operator was not found among band candidates (or was not set). Falling back to first candidate: {} ('{}')",
                 fallbackOperator.getId(), fallbackOperator.getName());
        return Optional.of(fallbackOperator);
    }

    @Transactional(readOnly = true)
    public String findOperatorNameById(Long operatorId) {
        if (operatorId == null || operatorId == 0L) {
            return "Unknown Operator";
        }
        try {
            Operator operator = entityManager.find(Operator.class, operatorId);
            return operator != null ? operator.getName() : "OperatorID:" + operatorId;
        } catch (Exception e) {
            log.warn("Could not find operator name for ID: {}", operatorId, e);
            return "OperatorID:" + operatorId;
        }
    }
}