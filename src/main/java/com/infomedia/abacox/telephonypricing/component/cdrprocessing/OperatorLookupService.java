// File: com/infomedia/abacox/telephonypricing/cdr/OperatorLookupService.java
package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import com.infomedia.abacox.telephonypricing.entity.Operator;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@Log4j2
public class OperatorLookupService {

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(readOnly = true)
    public Optional<OperatorInfo> findOperatorById(Long operatorId) {
        if (operatorId == null || operatorId == 0L) {
            return Optional.empty();
        }
        try {
            Operator operator = entityManager.find(Operator.class, operatorId);
            if (operator != null && operator.isActive()) {
                return Optional.of(new OperatorInfo(operator.getId(), operator.getName()));
            }
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Could not find operator for ID: {}", operatorId, e);
            return Optional.empty();
        }
    }

    /**
     * Finds an operator for an incoming cellular call based on the destination indicator's bands.
     * This replicates the specific PHP logic:
     * $sql_op = "SELECT PREFIJO_OPERADOR_ID FROM banda, bandaindica, prefijo
     * WHERE BANDAINDICA_INDICATIVO_ID = ".$indicativo_destino."
     * AND BANDA_ID = BANDAINDICA_BANDA_ID AND PREFIJO_ID = BANDA_PREFIJO_ID";
     *
     * @param destinationIndicatorId The indicator ID of the (incoming) cellular number.
     * @return Optional<OperatorInfo>
     */
    @Transactional(readOnly = true)
    public Optional<OperatorInfo> findOperatorForIncomingCellularByIndicatorBands(Long destinationIndicatorId) {
        if (destinationIndicatorId == null || destinationIndicatorId <= 0) {
            return Optional.empty();
        }

        String queryStr = "SELECT DISTINCT p.operator_id as operator_id, op.name as operator_name " +
                          "FROM band b " +
                          "JOIN band_indicator bi ON b.id = bi.band_id " +
                          "JOIN prefix p ON b.prefix_id = p.id " +
                          "JOIN operator op ON p.operator_id = op.id " +
                          "WHERE bi.indicator_id = :destinationIndicatorId " +
                          "AND b.active = true AND p.active = true AND op.active = true " +
                          "AND p.telephony_type_id = :cellularTelephonyTypeId " + // Ensure the prefix is for cellular
                          "LIMIT 1"; // PHP takes the first one found

        jakarta.persistence.Query nativeQuery = entityManager.createNativeQuery(queryStr, Tuple.class);
        nativeQuery.setParameter("destinationIndicatorId", destinationIndicatorId);
        nativeQuery.setParameter("cellularTelephonyTypeId", TelephonyTypeEnum.CELLULAR.getValue());

        try {
            Tuple result = (Tuple) nativeQuery.getSingleResult();
            Long operatorId = result.get("operator_id", Number.class).longValue();
            String operatorName = result.get("operator_name", String.class);
            if (operatorId > 0) {
                log.debug("Found operator {} ({}) for incoming cellular via indicator {} bands.",
                        operatorName, operatorId, destinationIndicatorId);
                return Optional.of(new OperatorInfo(operatorId, operatorName));
            }
        } catch (NoResultException e) {
            log.debug("No operator found for incoming cellular via indicator {} bands.", destinationIndicatorId);
        }
        return Optional.empty();
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