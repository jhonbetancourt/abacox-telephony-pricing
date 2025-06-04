package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import com.infomedia.abacox.telephonypricing.entity.SpecialService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;


@Service
@Log4j2
@RequiredArgsConstructor
public class SpecialServiceLookupService {

    @PersistenceContext
    private EntityManager entityManager;
    private final TelephonyTypeLookupService telephonyTypeLookupService;

    @Transactional(readOnly = true)
    public Optional<SpecialServiceInfo> findSpecialService(String phoneNumber, Long indicatorId, Long originCountryId) {
        // PHP's buscar_NumeroEspecial and CargarServEspeciales
        String queryStr = "SELECT ss.* FROM special_service ss " +
                          "WHERE ss.active = true AND ss.phone_number = :phoneNumber " +
                          "AND (ss.indicator_id = 0 OR ss.indicator_id = :indicatorId) " + // 0 for global special numbers
                          "AND ss.origin_country_id = :originCountryId " +
                          "ORDER BY ss.indicator_id DESC LIMIT 1"; // Prefer specific indicator over global

        jakarta.persistence.Query nativeQuery = entityManager.createNativeQuery(queryStr, SpecialService.class);
        nativeQuery.setParameter("phoneNumber", phoneNumber);
        nativeQuery.setParameter("indicatorId", indicatorId);
        nativeQuery.setParameter("originCountryId", originCountryId);

        try {
            SpecialService ss = (SpecialService) nativeQuery.getSingleResult();
            SpecialServiceInfo ssi = new SpecialServiceInfo();
            ssi.phoneNumber = ss.getPhoneNumber();
            ssi.value = ss.getValue();
            ssi.vatRate = ss.getVatAmount(); // PHP uses SERVESPECIAL_IVA, which seems to be the rate not amount
            ssi.vatIncluded = ss.getVatIncluded();
            ssi.description = ss.getDescription();
            
            // PHP's operador_interno logic for _TIPOTELE_ESPECIALES
            OperatorInfo internalOp = telephonyTypeLookupService.getInternalOperatorInfo(
                TelephonyTypeEnum.SPECIAL_SERVICES.getValue(), originCountryId
            );
            ssi.operatorId = internalOp.getId();
            ssi.operatorName = internalOp.getName();

            return Optional.of(ssi);
        } catch (NoResultException e) {
            return Optional.empty();
        }
    }
}