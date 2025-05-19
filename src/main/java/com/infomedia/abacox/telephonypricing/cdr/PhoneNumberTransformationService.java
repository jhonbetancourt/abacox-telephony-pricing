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
    private final IndicatorLookupService indicatorLookupService; // For findLocalNdcForIndicator
    // private final TelephonyTypeLookupService telephonyTypeLookupService; // Not directly used here but good for context

    /**
     * PHP equivalent: _esEntrante_60
     */
    public TransformationResult transformIncomingNumberCME(String phoneNumber, Long originCountryId) {
        if (phoneNumber == null) return new TransformationResult(null, false, null);
        String originalPhoneNumber = phoneNumber;
        String transformedNumber = phoneNumber;
        Long newTelephonyTypeId = null; // This function in PHP hints at a type via $g_tipotele

        // PHP: if($directorio['MPORIGEN_ID'] == 1){
        if (originCountryId != null && originCountryId == 1L) { // Colombia MPORIGEN_ID = 1
            int len = phoneNumber.length();
            String p2 = len >= 2 ? phoneNumber.substring(0, 2) : "";
            String p3 = len >= 3 ? phoneNumber.substring(0, 3) : "";
            String p4 = len >= 4 ? phoneNumber.substring(0, 4) : "";

            if (len == 12) {
                // PHP: if($p603 == 573 || $p603 == 603){ $telefono = substr($telefono, -10); $g_tipotele = 2; }
                if ("573".equals(p3) || "603".equals(p3)) {
                    transformedNumber = phoneNumber.substring(len - 10);
                    newTelephonyTypeId = TelephonyTypeEnum.CELLULAR.getValue();
                }
                // PHP: else{ $p6060 = substr($telefono, 0, 4); if($p6060 == 6060 || $p6060 == 5760){ $telefono = substr($telefono, -8); } }
                else if ("6060".equals(p4) || "5760".equals(p4)) {
                    transformedNumber = phoneNumber.substring(len - 8);
                }
            } else if (len == 11) {
                // PHP: if($p603 == 604){ $telefono = substr($telefono, -8); }
                if ("604".equals(p3)) { // Example for a specific fixed line prefix
                    transformedNumber = phoneNumber.substring(len - 8);
                }
                // PHP: else{ if($p60 == 03){ $n3 = substr($telefono, 1, 3); if($n3 >= 300 && $n3 <= 350){ $telefono = substr($telefono, -10); $g_tipotele = 2; } } }
                else if ("03".equals(p2)) { // Example: 03xxxxxxxxx (cellular with leading 0)
                    String n3_digits = (len > 3) ? phoneNumber.substring(1, 4) : "";
                    try {
                        if (!n3_digits.isEmpty()) {
                            int n3val = Integer.parseInt(n3_digits);
                            if (n3val >= 300 && n3val <= 350) { // Common cellular prefixes in CO
                                transformedNumber = phoneNumber.substring(len - 10);
                                newTelephonyTypeId = TelephonyTypeEnum.CELLULAR.getValue();
                            }
                        }
                    } catch (NumberFormatException | StringIndexOutOfBoundsException ignored) {}
                }
            } else if (len == 10) {
                // PHP: if($len == 10 && ($p60 == 60 || $p60 == 57)){ $telefono = substr($telefono, -8); }
                if ("60".equals(p2) || "57".equals(p2)) { // National with new prefix
                    transformedNumber = phoneNumber.substring(len - 8); // Assuming 2-digit prefix + 1-digit NDC + 7-digit number
                }
                // PHP: else{ if($primer == 3){ $n3 = substr($telefono, 0, 3); if($n3 >= 300 && $n3 <= 350){ $g_tipotele = 2; } } }
                else if (phoneNumber.startsWith("3")) { // Cellular 3xxxxxxxxx
                    try {
                        int n3val = Integer.parseInt(phoneNumber.substring(0, 3));
                        if (n3val >= 300 && n3val <= 350) {
                            newTelephonyTypeId = TelephonyTypeEnum.CELLULAR.getValue();
                        }
                    } catch (NumberFormatException | StringIndexOutOfBoundsException ignored) {}
                }
            } else if (len == 9) { // National with 9 digits (e.g. 60XNNNNNN)
                // PHP: if($len == 9){//Nacional con 9 digitos if($p60 == 60 ){ $telefono = substr($telefono, -7); } }
                if ("60".equals(p2)) {
                    transformedNumber = phoneNumber.substring(len - 7); // Assuming 2-digit prefix + 7-digit number
                }
            }
        }
        return new TransformationResult(transformedNumber, !transformedNumber.equals(originalPhoneNumber), newTelephonyTypeId);
    }

    /**
     * PHP equivalent: _es_Saliente
     */
    public TransformationResult transformOutgoingNumberCME(String phoneNumber, Long originCountryId) {
        if (phoneNumber == null) return new TransformationResult(null, false, null);
        String originalPhoneNumber = phoneNumber;
        String transformedNumber = phoneNumber;

        // PHP: if($directorio['MPORIGEN_ID'] == 1){
        if (originCountryId != null && originCountryId == 1L) { // Colombia
            int len = phoneNumber.length();
            // PHP: if($slen == 11){ if($snum2 == 03){ $sn3 = substr($stelefono, 1, 3); if($sn3 >= 300 && $sn3 <= 350){ $stelefono = substr($stelefono, -10); } } }
            if (len == 11 && phoneNumber.startsWith("03")) { // e.g. 0300xxxxxxx
                try {
                    int n3val = Integer.parseInt(phoneNumber.substring(1, 4)); // Check 300 part
                    if (n3val >= 300 && n3val <= 350) { // Common cellular prefixes
                        transformedNumber = phoneNumber.substring(len - 10); // Strip leading '0'
                    }
                } catch (NumberFormatException | StringIndexOutOfBoundsException ignored) {}
            }
        }
        return new TransformationResult(transformedNumber, !transformedNumber.equals(originalPhoneNumber), null);
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

        // PHP: if($directorio['MPORIGEN_ID'] == 1){
        if (commLocation.getIndicator().getOriginCountryId() == 1L) { // Colombia
            int len = phoneNumber.length();
            if (len == 10) {
                // PHP: if($primer == 3){ $g_numero = $numero = "03".$numero; }
                if (phoneNumber.startsWith("3")) { // Cellular: 3xxxxxxxxx
                    transformedNumber = "03" + phoneNumber; // Add "03" for prefix matching logic
                    newTelephonyTypeId = TelephonyTypeEnum.CELLULAR.getValue();
                }
                // PHP: else{ $p60 = substr($numero, 0, -8); if($p60 == 60){ ... } }
                else if (phoneNumber.startsWith("60")) { // New fixed line format: 60Nxxxxxxx
                    String ndcPart = phoneNumber.substring(2, 3); // e.g., "1" for Bogota from "601"
                    String subscriberPart = phoneNumber.substring(3);

                    // PHP: $sql = "SELECT INDICATIVO_DPTO_PAIS, INDICATIVO_CIUDAD, SERIE_EMPRESA FROM serie, indicativo WHERE ..."
                    String seriesLookupQuery = "SELECT i.department_country, i.city_name, s.company " +
                            "FROM series s JOIN indicator i ON s.indicator_id = i.id " +
                            "WHERE i.telephony_type_id = :nationalType AND s.ndc = :ndcPartInt " +
                            "  AND s.active = true AND i.active = true " +
                            "  AND s.initial_number <= :subscriberNum AND s.final_number >= :subscriberNum " +
                            // PHP: AND (INDICATIVO_OPERADOR_ID = 0 OR INDICATIVO_OPERADOR_ID in (SELECT PREFIJO_OPERADOR_ID FROM prefijo WHERE PREFIJO_ID = 7000012))
                            // The PREFIJO_ID = 7000012 is very specific. Assuming it means a default national operator or any.
                            // For simplicity, we'll omit this specific operator check unless it's crucial and can be generalized.
                            "  AND i.origin_country_id = 1 " + // Colombia
                            "LIMIT 1";
                    jakarta.persistence.Query nativeQuery = entityManager.createNativeQuery(seriesLookupQuery, Tuple.class);
                    nativeQuery.setParameter("nationalType", TelephonyTypeEnum.NATIONAL.getValue());
                    try {
                        nativeQuery.setParameter("ndcPartInt", Integer.parseInt(ndcPart));
                        nativeQuery.setParameter("subscriberNum", Integer.parseInt(subscriberPart));
                    } catch (NumberFormatException e) {
                        log.warn("NDC part {} or Subscriber part {} is not a number for _esCelular_fijo logic", ndcPart, subscriberPart);
                        return new TransformationResult(originalPhoneNumber, false, null); // Return original if parts are not numeric
                    }

                    try {
                        Tuple seriesData = (Tuple) nativeQuery.getSingleResult();
                        String dbDept = seriesData.get("department_country", String.class);
                        String dbCity = seriesData.get("city_name", String.class);
                        String dbCompany = seriesData.get("company", String.class);

                        Indicator plantIndicator = commLocation.getIndicator();
                        // PHP: if($fila['INDICATIVO_DPTO_PAIS'] == $g_dep && $fila['INDICATIVO_CIUDAD'] == $g_ciudad){ $g_numero = $numero = substr($numero, 3); return $numero;}
                        if (Objects.equals(dbDept, plantIndicator.getDepartmentCountry()) &&
                                Objects.equals(dbCity, plantIndicator.getCityName())) {
                            transformedNumber = subscriberPart; // Treat as local
                            newTelephonyTypeId = TelephonyTypeEnum.LOCAL.getValue();
                        }
                        // PHP: else{ if($fila['INDICATIVO_DPTO_PAIS'] == $g_dep){ $g_numero = $numero = substr($numero, 3); return $numero;}
                        else if (Objects.equals(dbDept, plantIndicator.getDepartmentCountry())) {
                            transformedNumber = subscriberPart;
                            newTelephonyTypeId = TelephonyTypeEnum.LOCAL_EXTENDED.getValue();
                        }
                        // PHP: else{ if($ind != ''){ $numero = substr($numero, 2); $g_numero = $numero = $ind.$numero; }
                        else {
                            String operatorPrefix = mapCompanyToOperatorPrefix(dbCompany); // PHP: $ind = _esNacional($fila);
                            if (!operatorPrefix.isEmpty()) {
                                transformedNumber = operatorPrefix + phoneNumber.substring(2); // remove "60"
                            }
                            // PHP: else{ $numero = substr($numero, 2); $g_numero = $numero = '09'.$numero; }
                            else {
                                transformedNumber = "09" + phoneNumber.substring(2); // Default if no mapping
                            }
                            newTelephonyTypeId = TelephonyTypeEnum.NATIONAL.getValue();
                        }
                    } catch (NoResultException e) {
                        // PHP: else{ $numero = substr($numero, 2); $g_numero = $numero = '09'.$numero; }
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
        // These mappings are specific and might need to be configurable or more robust
        if (upperCompany.contains("TELMEX")) return "0456"; // CLARO HOGAR FIJO
        if (upperCompany.contains("COLOMBIA TELECOMUNICACIONES S.A. ESP")) return "09"; // MOVISTAR FIJO
        if (upperCompany.contains("UNE EPM TELECOMUNICACIONES S.A. E.S.P.") || upperCompany.contains("UNE EPM TELCO S.A.")) return "05"; // TIGO-UNE FIJO
        if (upperCompany.contains("EMPRESA DE TELECOMUNICACIONES DE BOGOTÁ S.A. ESP.") || upperCompany.contains("ETB")) return "07"; // ETB FIJO
        return ""; // Default if no specific mapping
    }
}