package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.CommunicationLocation;
import com.infomedia.abacox.telephonypricing.entity.Indicator;
import com.infomedia.abacox.telephonypricing.entity.Series;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.util.Objects;
import java.util.Optional;

@Service
@Log4j2
@RequiredArgsConstructor
public class PhoneNumberTransformationService {

    @PersistenceContext
    private EntityManager entityManager;
    private final IndicatorLookupService indicatorLookupService;


    public TransformationResult transformIncomingNumber(String phoneNumber, Long originCountryId) {
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
                if ("604".equals(p3)) {
                    phoneNumber = phoneNumber.substring(len - 8);
                } else if ("03".equals(p2)) {
                    String n3_digits = phoneNumber.substring(1, 4);
                    try {
                        int n3val = Integer.parseInt(n3_digits);
                        if (n3val >= 300 && n3val <= 350) {
                            phoneNumber = phoneNumber.substring(len - 10);
                            newTelephonyTypeId = TelephonyTypeEnum.CELLULAR.getValue();
                        }
                    } catch (NumberFormatException | StringIndexOutOfBoundsException ignored) {}
                }
            } else if (len == 10) {
                if ("60".equals(p2) || "57".equals(p2)) {
                    phoneNumber = phoneNumber.substring(len - 8);
                } else if (phoneNumber.startsWith("3")) {
                    try {
                        int n3val = Integer.parseInt(phoneNumber.substring(0, 3));
                        if (n3val >= 300 && n3val <= 350) {
                            newTelephonyTypeId = TelephonyTypeEnum.CELLULAR.getValue();
                        }
                    } catch (NumberFormatException | StringIndexOutOfBoundsException ignored) {}
                }
            } else if (len == 9) {
                if ("60".equals(p2)) {
                    phoneNumber = phoneNumber.substring(len - 7);
                }
            }
        }
        return new TransformationResult(phoneNumber, !phoneNumber.equals(originalPhoneNumber), newTelephonyTypeId);
    }

    public TransformationResult transformOutgoingNumber(String phoneNumber, Long originCountryId) {
        if (phoneNumber == null) return new TransformationResult(null, false, null);
        String originalPhoneNumber = phoneNumber;

        if (originCountryId != null && originCountryId == 1L) { // Colombia
            int len = phoneNumber.length();
            if (len == 11 && phoneNumber.startsWith("03")) {
                try {
                    int n3val = Integer.parseInt(phoneNumber.substring(1, 4));
                    if (n3val >= 300 && n3val <= 350) {
                        phoneNumber = phoneNumber.substring(len - 10);
                    }
                } catch (NumberFormatException | StringIndexOutOfBoundsException ignored) {}
            }
        }
        return new TransformationResult(phoneNumber, !phoneNumber.equals(originalPhoneNumber), null);
    }

    @Transactional(readOnly = true) // Added for DB access
    public TransformationResult transformForPrefixLookup(String phoneNumber, CommunicationLocation commLocation) {
        if (phoneNumber == null || commLocation == null || commLocation.getIndicator() == null ||
            commLocation.getIndicator().getOriginCountryId() == null) {
            return new TransformationResult(phoneNumber, false, null);
        }
        String originalPhoneNumber = phoneNumber;
        Long newTelephonyTypeId = null;

        if (commLocation.getIndicator().getOriginCountryId() == 1L) { // Colombia
            int len = phoneNumber.length();
            if (len == 10) {
                if (phoneNumber.startsWith("3")) { // Cellular
                    phoneNumber = "03" + phoneNumber;
                    newTelephonyTypeId = TelephonyTypeEnum.CELLULAR.getValue();
                } else if (phoneNumber.startsWith("60")) { // New fixed line format "60Nxxxxxxx"
                    String ndcPart = phoneNumber.substring(2, 3); // e.g., "1" for Bogota from "601"
                    String subscriberPart = phoneNumber.substring(3);

                    // PHP's _esCelular_fijo DB lookup part
                    String seriesLookupQuery = "SELECT i.department_country, i.city_name, s.company " +
                                               "FROM series s JOIN indicator i ON s.indicator_id = i.id " +
                                               "WHERE i.telephony_type_id = :nationalType AND s.ndc = :ndcPart " +
                                               "  AND s.active = true AND i.active = true " +
                                               "  AND s.initial_number <= :subscriberNum AND s.final_number >= :subscriberNum " +
                                               "  AND i.origin_country_id = 1 " + // Colombia
                                               "LIMIT 1";
                    jakarta.persistence.Query nativeQuery = entityManager.createNativeQuery(seriesLookupQuery, Tuple.class);
                    nativeQuery.setParameter("nationalType", TelephonyTypeEnum.NATIONAL.getValue());
                    nativeQuery.setParameter("ndcPart", ndcPart);
                    try {
                        nativeQuery.setParameter("subscriberNum", Integer.parseInt(subscriberPart));
                    } catch (NumberFormatException e) {
                         log.warn("Subscriber part {} is not a number for _esCelular_fijo logic", subscriberPart);
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
                            phoneNumber = subscriberPart; // Treat as local
                            newTelephonyTypeId = TelephonyTypeEnum.LOCAL.getValue();
                        } else if (Objects.equals(dbDept, plantIndicator.getDepartmentCountry())) {
                            // PHP: if($fila['INDICATIVO_DPTO_PAIS'] == $g_dep){ $g_numero = $numero = substr($numero, 3); }
                            // This means if same department but different city, it's still treated as local for prefix matching.
                            phoneNumber = subscriberPart;
                            newTelephonyTypeId = TelephonyTypeEnum.LOCAL_EXTENDED.getValue(); // Or just LOCAL for prefix matching
                        }
                        else {
                            // National, transform using operator prefix from SERIE_EMPRESA
                            String operatorPrefix = mapCompanyToOperatorPrefix(dbCompany);
                            if (!operatorPrefix.isEmpty()) {
                                phoneNumber = operatorPrefix + phoneNumber.substring(2); // remove "60"
                            } else {
                                phoneNumber = "09" + phoneNumber.substring(2); // Default if no mapping
                            }
                            newTelephonyTypeId = TelephonyTypeEnum.NATIONAL.getValue();
                        }
                    } catch (NoResultException e) {
                        // No match in series, default to national with "09" prefix (PHP behavior)
                        phoneNumber = "09" + phoneNumber.substring(2); // remove "60"
                        newTelephonyTypeId = TelephonyTypeEnum.NATIONAL.getValue();
                    }
                }
            }
        }
        return new TransformationResult(phoneNumber, !phoneNumber.equals(originalPhoneNumber), newTelephonyTypeId);
    }

    private String mapCompanyToOperatorPrefix(String companyName) {
        // Mimics PHP's _esNacional
        if (companyName == null) return "";
        String upperCompany = companyName.toUpperCase();
        if (upperCompany.contains("TELMEX")) return "0456"; // CLARO HOGAR FIJO
        if (upperCompany.contains("COLOMBIA TELECOMUNICACIONES")) return "09"; // MOVISTAR FIJO
        if (upperCompany.contains("UNE EPM") || upperCompany.contains("UNE EPM TELCO")) return "05"; // TIGO-UNE FIJO
        if (upperCompany.contains("EMPRESA DE TELECOMUNICACIONES DE BOGOTÁ") || upperCompany.contains("ETB")) return "07"; // ETB FIJO
        return ""; // Default or unknown
    }
}