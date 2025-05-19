// File: com/infomedia/abacox/telephonypricing/cdr/PhoneNumberTransformationService.java
package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.CommunicationLocation; // Added for transformForPrefixLookup
import com.infomedia.abacox.telephonypricing.entity.Indicator;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@Log4j2
@RequiredArgsConstructor
public class PhoneNumberTransformationService {

    @PersistenceContext
    private EntityManager entityManager;
    private final IndicatorLookupService indicatorLookupService;
    private final TelephonyTypeLookupService telephonyTypeLookupService;

    /**
     * PHP equivalent: _esEntrante_60
     */
    public TransformationResult transformIncomingNumberCME(String phoneNumber, Long originCountryId) {
        if (phoneNumber == null) return new TransformationResult(null, false, null);
        String originalPhoneNumber = phoneNumber;
        Long newTelephonyTypeId = null;

        if (originCountryId != null && originCountryId == 1L) { // Colombia MPORIGEN_ID = 1
            int len = phoneNumber.length();
            String p2 = len >= 2 ? phoneNumber.substring(0, 2) : "";
            String p3 = len >= 3 ? phoneNumber.substring(0, 3) : "";
            String p4 = len >= 4 ? phoneNumber.substring(0, 4) : "";

            if (len == 12) {
                if ("573".equals(p3) || "603".equals(p3)) {
                    phoneNumber = phoneNumber.substring(len - 10);
                    newTelephonyTypeId = TelephonyTypeEnum.CELLULAR.getValue();
                } else if ("6060".equals(p4) || "5760".equals(p4)) {
                    phoneNumber = phoneNumber.substring(len - 8);
                }
            } else if (len == 11) {
                if ("604".equals(p3)) { // Example for a specific fixed line prefix
                    phoneNumber = phoneNumber.substring(len - 8);
                } else if ("03".equals(p2)) { // Example: 03xxxxxxxxx (cellular with leading 0)
                    String n3_digits = (len > 3) ? phoneNumber.substring(1, 4) : "";
                    try {
                        if (!n3_digits.isEmpty()) {
                            int n3val = Integer.parseInt(n3_digits);
                            if (n3val >= 300 && n3val <= 350) { // Common cellular prefixes in CO
                                phoneNumber = phoneNumber.substring(len - 10);
                                newTelephonyTypeId = TelephonyTypeEnum.CELLULAR.getValue();
                            }
                        }
                    } catch (NumberFormatException | StringIndexOutOfBoundsException ignored) {}
                }
            } else if (len == 10) {
                if ("60".equals(p2) || "57".equals(p2)) { // National with new prefix
                    phoneNumber = phoneNumber.substring(len - 8); // Assuming 2-digit prefix + 1-digit NDC + 7-digit number
                } else if (phoneNumber.startsWith("3")) { // Cellular 3xxxxxxxxx
                    try {
                        int n3val = Integer.parseInt(phoneNumber.substring(0, 3));
                        if (n3val >= 300 && n3val <= 350) {
                            newTelephonyTypeId = TelephonyTypeEnum.CELLULAR.getValue();
                        }
                    } catch (NumberFormatException | StringIndexOutOfBoundsException ignored) {}
                }
            } else if (len == 9) { // National with 9 digits (e.g. 60XNNNNNN)
                if ("60".equals(p2)) {
                    phoneNumber = phoneNumber.substring(len - 7); // Assuming 2-digit prefix + 7-digit number
                }
            }
        }
        return new TransformationResult(phoneNumber, !phoneNumber.equals(originalPhoneNumber), newTelephonyTypeId);
    }

    /**
     * PHP equivalent: _es_Saliente
     */
    public TransformationResult transformOutgoingNumberCME(String phoneNumber, Long originCountryId) {
        if (phoneNumber == null) return new TransformationResult(null, false, null);
        String originalPhoneNumber = phoneNumber;

        if (originCountryId != null && originCountryId == 1L) { // Colombia
            int len = phoneNumber.length();
            if (len == 11 && phoneNumber.startsWith("03")) { // e.g. 0300xxxxxxx
                try {
                    int n3val = Integer.parseInt(phoneNumber.substring(1, 4)); // Check 300 part
                    if (n3val >= 300 && n3val <= 350) {
                        phoneNumber = phoneNumber.substring(len - 10); // Strip leading '0'
                    }
                } catch (NumberFormatException | StringIndexOutOfBoundsException ignored) {}
            }
        }
        return new TransformationResult(phoneNumber, !phoneNumber.equals(originalPhoneNumber), null);
    }

    /**
     * PHP equivalent: _esCelular_fijo
     */
    @Transactional(readOnly = true)
    public TransformationResult transformForPrefixLookup(String phoneNumber, CommunicationLocation commLocation) {
        if (phoneNumber == null || commLocation == null || commLocation.getIndicator() == null ||
            commLocation.getIndicator().getOriginCountryId() == null) {
            return new TransformationResult(phoneNumber, false, null);
        }

        String originalPhoneNumber = phoneNumber;
        String transformedNumber = phoneNumber;
        Long newTelephonyTypeId = null;

        if (commLocation.getIndicator().getOriginCountryId() == 1L) { // Colombia
            int len = phoneNumber.length();
            if (len == 10) {
                if (phoneNumber.startsWith("3")) { // Cellular: 3xxxxxxxxx
                    transformedNumber = "03" + phoneNumber; // Add "03" for prefix matching logic
                    newTelephonyTypeId = TelephonyTypeEnum.CELLULAR.getValue();
                } else if (phoneNumber.startsWith("60")) { // New fixed line format: 60Nxxxxxxx
                    String ndcPart = phoneNumber.substring(2, 3); // e.g., "1" for Bogota from "601"
                    String subscriberPart = phoneNumber.substring(3);

                    String seriesLookupQuery = "SELECT i.department_country, i.city_name, s.company " +
                                               "FROM series s JOIN indicator i ON s.indicator_id = i.id " +
                                               "WHERE i.telephony_type_id = :nationalType AND s.ndc = :ndcPartInt " +
                                               "  AND s.active = true AND i.active = true " +
                                               "  AND s.initial_number <= :subscriberNum AND s.final_number >= :subscriberNum " +
                                               "  AND i.origin_country_id = 1 " + // Colombia
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

                        Indicator plantIndicator = commLocation.getIndicator();
                        if (Objects.equals(dbDept, plantIndicator.getDepartmentCountry()) &&
                            Objects.equals(dbCity, plantIndicator.getCityName())) {
                            transformedNumber = subscriberPart; // Treat as local
                            newTelephonyTypeId = TelephonyTypeEnum.LOCAL.getValue();
                        } else if (Objects.equals(dbDept, plantIndicator.getDepartmentCountry())) {
                            // PHP: if($fila['INDICATIVO_DPTO_PAIS'] == $g_dep){ $g_numero = $numero = substr($numero, 3); return $numero;}
                            // This means it's treated as local for prefix matching if in same department
                            transformedNumber = subscriberPart;
                            newTelephonyTypeId = TelephonyTypeEnum.LOCAL_EXTENDED.getValue(); // Or LOCAL, depending on how prefix matching handles it
                        } else {
                            // PHP: if($ind != ''){ $numero = substr($numero, 2); $g_numero = $numero = $ind.$numero; }
                            // PHP: else { $numero = substr($numero, 2); $g_numero = $numero = '09'.$numero; }
                            String operatorPrefix = mapCompanyToOperatorPrefix(dbCompany);
                            if (!operatorPrefix.isEmpty()) {
                                transformedNumber = operatorPrefix + phoneNumber.substring(2); // remove "60"
                            } else {
                                transformedNumber = "09" + phoneNumber.substring(2); // Default if no mapping
                            }
                            newTelephonyTypeId = TelephonyTypeEnum.NATIONAL.getValue();
                        }
                    } catch (NoResultException e) {
                        // PHP: else { $numero = substr($numero, 2); $g_numero = $numero = '09'.$numero; }
                        transformedNumber = "09" + phoneNumber.substring(2); // Default to national with "09"
                        newTelephonyTypeId = TelephonyTypeEnum.NATIONAL.getValue();
                    }
                }
            }
        }
        return new TransformationResult(transformedNumber, !transformedNumber.equals(originalPhoneNumber), newTelephonyTypeId);
    }

    /**
     * PHP equivalent: _esNacional
     */
    private String mapCompanyToOperatorPrefix(String companyName) {
        if (companyName == null) return "";
        String upperCompany = companyName.toUpperCase();
        if (upperCompany.contains("TELMEX")) return "0456"; // CLARO HOGAR FIJO
        if (upperCompany.contains("COLOMBIA TELECOMUNICACIONES S.A. ESP")) return "09"; // MOVISTAR FIJO
        if (upperCompany.contains("UNE EPM TELECOMUNICACIONES S.A. E.S.P.") || upperCompany.contains("UNE EPM TELCO S.A.")) return "05"; // TIGO-UNE FIJO
        if (upperCompany.contains("EMPRESA DE TELECOMUNICACIONES DE BOGOTÁ S.A. ESP.") || upperCompany.contains("ETB")) return "07"; // ETB FIJO
        return ""; // Default if no specific mapping
    }
}