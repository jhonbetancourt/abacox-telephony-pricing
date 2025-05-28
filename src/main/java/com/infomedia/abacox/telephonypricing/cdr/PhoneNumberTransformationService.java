// File: com/infomedia/abacox/telephonypricing/cdr/PhoneNumberTransformationService.java
package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.CommunicationLocation;
import com.infomedia.abacox.telephonypricing.entity.Indicator;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
@Log4j2
@RequiredArgsConstructor
public class PhoneNumberTransformationService {

    @PersistenceContext
    private EntityManager entityManager;
    private final IndicatorLookupService indicatorLookupService;

    public TransformationResult transformIncomingNumberCME(String phoneNumber, Long originCountryId) {
        if (phoneNumber == null) return new TransformationResult(null, false, null);
        String originalPhoneNumber = phoneNumber;
        String transformedNumber = phoneNumber;
        Long newTelephonyTypeId = null;
        log.debug("Transforming incoming CME number: '{}', countryId: {}", phoneNumber, originCountryId);

        if (originCountryId != null && originCountryId == 1L) { // Colombia MPORIGEN_ID = 1
            int len = phoneNumber.length();
            String p2 = len >= 2 ? phoneNumber.substring(0, 2) : "";
            String p3 = len >= 3 ? phoneNumber.substring(0, 3) : "";
            String p4 = len >= 4 ? phoneNumber.substring(0, 4) : "";

            if (len == 12) {
                if ("573".equals(p3) || "603".equals(p3)) {
                    transformedNumber = phoneNumber.substring(len - 10);
                    newTelephonyTypeId = TelephonyTypeEnum.CELLULAR.getValue();
                } else if ("6060".equals(p4) || "5760".equals(p4)) {
                    transformedNumber = phoneNumber.substring(len - 8);
                }
            } else if (len == 11) {
                if ("604".equals(p3)) { // Specific fixed line prefix (e.g., Antioquia)
                    transformedNumber = phoneNumber.substring(len - 8);
                } else if ("03".equals(p2)) { // Potential mobile starting with 03...
                    String n3_digits_after_0 = (len > 3) ? phoneNumber.substring(1, 4) : "";
                    try {
                        if (!n3_digits_after_0.isEmpty()) {
                            int n3val = Integer.parseInt(n3_digits_after_0);
                            if (n3val >= 300 && n3val <= 350) { // Mobile prefixes 300-350
                                transformedNumber = phoneNumber.substring(len - 10); // Get last 10 digits
                                newTelephonyTypeId = TelephonyTypeEnum.CELLULAR.getValue();
                            }
                        }
                    } catch (NumberFormatException | StringIndexOutOfBoundsException ignored) {
                        log.trace("NFE/SIOBE for 11-digit '03' number: {}", phoneNumber);
                    }
                }
            } else if (len == 10) {
                if ("60".equals(p2) || "57".equals(p2)) { // National fixed line with country/new prefix
                    transformedNumber = phoneNumber.substring(len - 8);
                } else if (phoneNumber.startsWith("3")) { // Mobile number
                    try {
                        int n3val = Integer.parseInt(phoneNumber.substring(0, 3));
                        if (n3val >= 300 && n3val <= 350) {
                            newTelephonyTypeId = TelephonyTypeEnum.CELLULAR.getValue();
                            // transformedNumber remains the same 10-digit mobile number
                        }
                    } catch (NumberFormatException | StringIndexOutOfBoundsException ignored) {
                        log.trace("NFE/SIOBE for 10-digit '3' number: {}", phoneNumber);
                    }
                }
            } else if (len == 9) {
                // PHP: if($len == 9){//Nacional con 9 digitos if($p60 == 60 ){ $telefono = substr($telefono, -7); } }
                // This implies a 7-digit subscriber number after "60"
                if ("60".equals(p2)) {
                    transformedNumber = phoneNumber.substring(len - 7);
                }
            }
        }
        boolean transformed = !transformedNumber.equals(originalPhoneNumber);
        log.debug("Incoming CME number transformation result: original='{}', transformed='{}', newTypeHint={}, transformed={}",
                originalPhoneNumber, transformedNumber, newTelephonyTypeId, transformed);
        return new TransformationResult(transformedNumber, transformed, newTelephonyTypeId);
    }

    public TransformationResult transformOutgoingNumberCME(String phoneNumber, Long originCountryId) {
        if (phoneNumber == null) return new TransformationResult(null, false, null);
        String originalPhoneNumber = phoneNumber;
        String transformedNumber = phoneNumber;
        log.debug("Transforming outgoing CME number: '{}', countryId: {}", phoneNumber, originCountryId);

        if (originCountryId != null && originCountryId == 1L) { // Colombia
            int len = phoneNumber.length();
            if (len == 11 && phoneNumber.startsWith("03")) {
                try {
                    // PHP: $sn3 = substr($stelefono, 1, 3);
                    String sn3_digits_after_0 = phoneNumber.substring(1, 4);
                    int n3val = Integer.parseInt(sn3_digits_after_0);
                    if (n3val >= 300 && n3val <= 350) { // Mobile prefix
                        transformedNumber = phoneNumber.substring(len - 10); // Get last 10 digits
                    }
                } catch (NumberFormatException | StringIndexOutOfBoundsException ignored) {
                    log.trace("NFE/SIOBE for 11-digit outgoing '03' number: {}", phoneNumber);
                }
            }
        }
        boolean transformed = !transformedNumber.equals(originalPhoneNumber);
        log.debug("Outgoing CME number transformation result: original='{}', transformed='{}', transformed={}",
                originalPhoneNumber, transformedNumber, transformed);
        return new TransformationResult(transformedNumber, transformed, null);
    }

    @Transactional(readOnly = true)
    public TransformationResult transformForPrefixLookup(String phoneNumber, CommunicationLocation commLocation) {
        if (phoneNumber == null || commLocation == null || commLocation.getIndicator() == null ||
                commLocation.getIndicator().getOriginCountryId() == null) {
            return new TransformationResult(phoneNumber, false, null);
        }
        log.debug("Transforming for prefix lookup: number='{}', commLocationDir='{}'", phoneNumber, commLocation.getDirectory());

        String originalPhoneNumber = phoneNumber;
        String transformedNumber = phoneNumber;
        Long newTelephonyTypeId = null;

        if (commLocation.getIndicator().getOriginCountryId() == 1L) { // Colombia
            int len = phoneNumber.length();
            if (len == 10) {
                if (phoneNumber.startsWith("3")) {
                    transformedNumber = "03" + phoneNumber;
                    newTelephonyTypeId = TelephonyTypeEnum.CELLULAR.getValue();
                    log.debug("10-digit cellular '{}' transformed to '{}', type hint: CELLULAR", originalPhoneNumber, transformedNumber);
                }
                else if (phoneNumber.startsWith("60")) {
                    String ndcPart = phoneNumber.substring(2, 3);
                    String subscriberPart = phoneNumber.substring(3);
                    log.debug("10-digit fixed '60...' number. NDC part: '{}', Subscriber part: '{}'", ndcPart, subscriberPart);

                    String seriesLookupQuery = "SELECT i.department_country, i.city_name, s.company " +
                            "FROM series s JOIN indicator i ON s.indicator_id = i.id " +
                            "WHERE i.telephony_type_id = :nationalType AND s.ndc = :ndcPartInt " +
                            "  AND s.active = true AND i.active = true " +
                            "  AND s.initial_number <= :subscriberNum AND s.final_number >= :subscriberNum " +
                            "  AND i.origin_country_id = 1 " +
                            "LIMIT 1";
                    jakarta.persistence.Query nativeQuery = entityManager.createNativeQuery(seriesLookupQuery, Tuple.class);
                    nativeQuery.setParameter("nationalType", TelephonyTypeEnum.NATIONAL.getValue());
                    try {
                        nativeQuery.setParameter("ndcPartInt", Integer.parseInt(ndcPart));
                        nativeQuery.setParameter("subscriberNum", Integer.parseInt(subscriberPart));
                    } catch (NumberFormatException e) {
                        log.warn("NDC part {} or Subscriber part {} is not a number for _esCelular_fijo logic", ndcPart, subscriberPart);
                        return new TransformationResult(originalPhoneNumber, false, null);
                    }

                    try {
                        Tuple seriesData = (Tuple) nativeQuery.getSingleResult();
                        String dbDept = seriesData.get("department_country", String.class);
                        String dbCity = seriesData.get("city_name", String.class);
                        String dbCompany = seriesData.get("company", String.class);
                        log.debug("Series lookup result: Dept='{}', City='{}', Company='{}'", dbDept, dbCity, dbCompany);

                        Indicator plantIndicator = commLocation.getIndicator();
                        if (Objects.equals(dbDept, plantIndicator.getDepartmentCountry()) &&
                                Objects.equals(dbCity, plantIndicator.getCityName())) {
                            transformedNumber = subscriberPart;
                            newTelephonyTypeId = TelephonyTypeEnum.LOCAL.getValue();
                            log.debug("Matched plant's city/dept. Transformed to local: '{}', type hint: LOCAL", transformedNumber);
                        }
                        else if (Objects.equals(dbDept, plantIndicator.getDepartmentCountry())) {
                            transformedNumber = subscriberPart;
                            newTelephonyTypeId = TelephonyTypeEnum.LOCAL_EXTENDED.getValue();
                            log.debug("Matched plant's dept. Transformed to local extended: '{}', type hint: LOCAL_EXTENDED", transformedNumber);
                        }
                        else {
                            String operatorPrefix = mapCompanyToOperatorPrefix(dbCompany);
                            if (!operatorPrefix.isEmpty()) {
                                transformedNumber = operatorPrefix + phoneNumber.substring(2);
                            } else {
                                transformedNumber = "09" + phoneNumber.substring(2);
                            }
                            newTelephonyTypeId = TelephonyTypeEnum.NATIONAL.getValue();
                            log.debug("No local/extended match. Transformed to national: '{}' (prefix: '{}'), type hint: NATIONAL", transformedNumber, operatorPrefix.isEmpty() ? "09" : operatorPrefix);
                        }
                    } catch (NoResultException e) {
                        transformedNumber = "09" + phoneNumber.substring(2);
                        newTelephonyTypeId = TelephonyTypeEnum.NATIONAL.getValue();
                        log.warn("No series match for '60...' number. Defaulting to national: '{}', type hint: NATIONAL", transformedNumber);
                    }
                }
            }
        }
        boolean transformed = !transformedNumber.equals(originalPhoneNumber);
        log.debug("Prefix lookup transformation result: original='{}', transformed='{}', newTypeHint={}, transformed={}",
                originalPhoneNumber, transformedNumber, newTelephonyTypeId, transformed);
        return new TransformationResult(transformedNumber, transformed, newTelephonyTypeId);
    }

    private String mapCompanyToOperatorPrefix(String companyName) {
        if (companyName == null) return "";
        String upperCompany = companyName.toUpperCase();
        if (upperCompany.contains("TELMEX")) return "0456";
        if (upperCompany.contains("COLOMBIA TELECOMUNICACIONES S.A. ESP")) return "09";
        if (upperCompany.contains("UNE EPM TELECOMUNICACIONES S.A. E.S.P.") || upperCompany.contains("UNE EPM TELCO S.A.")) return "05";
        if (upperCompany.contains("EMPRESA DE TELECOMUNICACIONES DE BOGOTÁ S.A. ESP.") || upperCompany.contains("ETB")) return "07";
        return "";
    }
}