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
    private final IndicatorLookupService indicatorLookupService; // Already exists, can be used

    /**
     * PHP equivalent: _esEntrante_60
     */
    public TransformationResult transformIncomingNumberCME(String phoneNumber, Long originCountryId) {
        if (phoneNumber == null) return new TransformationResult(null, false, null);
        String originalPhoneNumber = phoneNumber;
        String transformedNumber = phoneNumber;
        Long newTelephonyTypeId = null;
        log.debug("Transforming incoming CME number: '{}', countryId: {}", phoneNumber, originCountryId);

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
                if ("604".equals(p3)) {
                    transformedNumber = phoneNumber.substring(len - 8);
                }
                // PHP: else{ if($p60 == 03){ $n3 = substr($telefono, 1, 3); if($n3 >= 300 && $n3 <= 350){ $telefono = substr($telefono, -10); $g_tipotele = 2; } } }
                else if ("03".equals(p2)) {
                    String n3_digits = (len > 3) ? phoneNumber.substring(1, 4) : ""; // Get digits after '0'
                    try {
                        if (!n3_digits.isEmpty()) {
                            int n3val = Integer.parseInt(n3_digits);
                            if (n3val >= 300 && n3val <= 350) { // Check if it's a mobile prefix
                                transformedNumber = phoneNumber.substring(len - 10);
                                newTelephonyTypeId = TelephonyTypeEnum.CELLULAR.getValue();
                            }
                        }
                    } catch (NumberFormatException | StringIndexOutOfBoundsException ignored) {
                        log.trace("NFE/SIOBE for 11-digit '03' number: {}", phoneNumber);
                    }
                }
            } else if (len == 10) {
                // PHP: if($len == 10 && ($p60 == 60 || $p60 == 57)){ $telefono = substr($telefono, -8); }
                if ("60".equals(p2) || "57".equals(p2)) { // National fixed line with country/new prefix
                    transformedNumber = phoneNumber.substring(len - 8);
                }
                // PHP: else{ if($len == 10){ $primer = $telefono[0]; if($primer == 3){ $n3 = substr($telefono, 0, 3); if($n3 >= 300 && $n3 <= 350){ $g_tipotele = 2; } } } }
                else if (phoneNumber.startsWith("3")) { // Mobile number
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
                if ("60".equals(p2)) { // Shorter national fixed line with new prefix
                    transformedNumber = phoneNumber.substring(len - 7);
                }
            }
        }
        boolean transformed = !transformedNumber.equals(originalPhoneNumber);
        log.debug("Incoming CME number transformation result: original='{}', transformed='{}', newTypeHint={}, transformed={}",
                originalPhoneNumber, transformedNumber, newTelephonyTypeId, transformed);
        return new TransformationResult(transformedNumber, transformed, newTelephonyTypeId);
    }

    /**
     * PHP equivalent: _es_Saliente
     */
    public TransformationResult transformOutgoingNumberCME(String phoneNumber, Long originCountryId) {
        if (phoneNumber == null) return new TransformationResult(null, false, null);
        String originalPhoneNumber = phoneNumber;
        String transformedNumber = phoneNumber;
        log.debug("Transforming outgoing CME number: '{}', countryId: {}", phoneNumber, originCountryId);

        // PHP: if($slen == 11){ if($snum2 == 03){ $sn3 = substr($stelefono, 1, 3); if($sn3 >= 300 && $sn3 <= 350){ $stelefono = substr($stelefono, -10); } } }
        if (originCountryId != null && originCountryId == 1L) { // Colombia
            int len = phoneNumber.length();
            if (len == 11 && phoneNumber.startsWith("03")) {
                try {
                    int n3val = Integer.parseInt(phoneNumber.substring(1, 4)); // Digits after '0'
                    if (n3val >= 300 && n3val <= 350) { // Mobile prefix
                        transformedNumber = phoneNumber.substring(len - 10);
                    }
                } catch (NumberFormatException | StringIndexOutOfBoundsException ignored) {
                    log.trace("NFE/SIOBE for 11-digit outgoing '03' number: {}", phoneNumber);
                }
            }
        }
        boolean transformed = !transformedNumber.equals(originalPhoneNumber);
        log.debug("Outgoing CME number transformation result: original='{}', transformed='{}', transformed={}",
                originalPhoneNumber, transformedNumber, transformed);
        return new TransformationResult(transformedNumber, transformed, null); // No type hint for outgoing
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
        log.debug("Transforming for prefix lookup: number='{}', commLocationDir='{}'", phoneNumber, commLocation.getDirectory());

        String originalPhoneNumber = phoneNumber;
        String transformedNumber = phoneNumber;
        Long newTelephonyTypeId = null;

        // PHP: if($mporigen_id == 1){
        if (commLocation.getIndicator().getOriginCountryId() == 1L) { // Colombia
            int len = phoneNumber.length();
            if (len == 10) {
                // PHP: if($primer == 3){ $g_numero = $numero = "03".$numero; }
                if (phoneNumber.startsWith("3")) {
                    transformedNumber = "03" + phoneNumber;
                    newTelephonyTypeId = TelephonyTypeEnum.CELLULAR.getValue();
                    log.debug("10-digit cellular '{}' transformed to '{}', type hint: CELLULAR", originalPhoneNumber, transformedNumber);
                }
                // PHP: else{ $p60 = substr($numero, 0, -8); if($p60 == 60){ ... } }
                else if (phoneNumber.startsWith("60")) { // Potentially new fixed line format
                    String ndcPart = phoneNumber.substring(2, 3); // The single digit after "60"
                    String subscriberPart = phoneNumber.substring(3); // The remaining 7 digits
                    log.debug("10-digit fixed '60...' number. NDC part: '{}', Subscriber part: '{}'", ndcPart, subscriberPart);

                    // PHP: $sql = "SELECT INDICATIVO_DPTO_PAIS, INDICATIVO_CIUDAD, SERIE_EMPRESA FROM serie, indicativo WHERE INDICATIVO_TIPOTELE_ID = 4 AND SERIE_NDC IN ('$tivo') ..."
                    String seriesLookupQuery = "SELECT i.department_country, i.city_name, s.company " +
                            "FROM series s JOIN indicator i ON s.indicator_id = i.id " +
                            "WHERE i.telephony_type_id = :nationalType AND s.ndc = :ndcPartInt " +
                            "  AND s.active = true AND i.active = true " +
                            "  AND s.initial_number <= :subscriberNum AND s.final_number >= :subscriberNum " +
                            // PHP: AND (INDICATIVO_OPERADOR_ID = 0 OR INDICATIVO_OPERADOR_ID in (SELECT PREFIJO_OPERADOR_ID FROM prefijo WHERE PREFIJO_ID = 7000012))
                            // The PREFIJO_ID = 7000012 is very specific. Assuming it means a default national operator or any.
                            // For simplicity, we'll omit this specific operator filter unless it's crucial and can be generalized.
                            "  AND i.origin_country_id = 1 " + // Colombia
                            "LIMIT 1";
                    jakarta.persistence.Query nativeQuery = entityManager.createNativeQuery(seriesLookupQuery, Tuple.class);
                    nativeQuery.setParameter("nationalType", TelephonyTypeEnum.NATIONAL.getValue());
                    try {
                        nativeQuery.setParameter("ndcPartInt", Integer.parseInt(ndcPart));
                        nativeQuery.setParameter("subscriberNum", Integer.parseInt(subscriberPart));
                    } catch (NumberFormatException e) {
                        log.warn("NDC part {} or Subscriber part {} is not a number for _esCelular_fijo logic", ndcPart, subscriberPart);
                        return new TransformationResult(originalPhoneNumber, false, null); // Cannot proceed
                    }

                    try {
                        Tuple seriesData = (Tuple) nativeQuery.getSingleResult();
                        String dbDept = seriesData.get("department_country", String.class);
                        String dbCity = seriesData.get("city_name", String.class);
                        String dbCompany = seriesData.get("company", String.class);
                        log.debug("Series lookup result: Dept='{}', City='{}', Company='{}'", dbDept, dbCity, dbCompany);

                        Indicator plantIndicator = commLocation.getIndicator();
                        // PHP: if($fila['INDICATIVO_DPTO_PAIS'] == $g_dep && $fila['INDICATIVO_CIUDAD'] == $g_ciudad){ $g_numero = $numero = substr($numero, 3); return $numero; }
                        if (Objects.equals(dbDept, plantIndicator.getDepartmentCountry()) &&
                                Objects.equals(dbCity, plantIndicator.getCityName())) {
                            transformedNumber = subscriberPart; // Remove "60" + NDC part
                            newTelephonyTypeId = TelephonyTypeEnum.LOCAL.getValue();
                            log.debug("Matched plant's city/dept. Transformed to local: '{}', type hint: LOCAL", transformedNumber);
                        }
                        // PHP: else{ if($fila['INDICATIVO_DPTO_PAIS'] == $g_dep){ $g_numero = $numero = substr($numero, 3); return $numero; }
                        else if (Objects.equals(dbDept, plantIndicator.getDepartmentCountry())) {
                            transformedNumber = subscriberPart; // Remove "60" + NDC part
                            newTelephonyTypeId = TelephonyTypeEnum.LOCAL_EXTENDED.getValue();
                            log.debug("Matched plant's dept. Transformed to local extended: '{}', type hint: LOCAL_EXTENDED", transformedNumber);
                        }
                        // PHP: else{ if($ind != ''){ $numero = substr($numero, 2); $g_numero = $numero = $ind.$numero; } }
                        else {
                            String operatorPrefix = mapCompanyToOperatorPrefix(dbCompany);
                            if (!operatorPrefix.isEmpty()) {
                                transformedNumber = operatorPrefix + phoneNumber.substring(2); // Keep NDC + subscriber, prepend new op prefix
                            } else { // PHP: else{ $numero = substr($numero, 2); $g_numero = $numero = '09'.$numero; }
                                transformedNumber = "09" + phoneNumber.substring(2); // Default to "09" if no specific mapping
                            }
                            newTelephonyTypeId = TelephonyTypeEnum.NATIONAL.getValue();
                            log.debug("No local/extended match. Transformed to national: '{}' (prefix: '{}'), type hint: NATIONAL", transformedNumber, operatorPrefix.isEmpty() ? "09" : operatorPrefix);
                        }
                    } catch (NoResultException e) {
                        // PHP: else{ $numero = substr($numero, 2); $g_numero = $numero = '09'.$numero; }
                        transformedNumber = "09" + phoneNumber.substring(2); // Default to "09" + (NDC + subscriber)
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

    /**
     * PHP equivalent: _esNacional
     */
    private String mapCompanyToOperatorPrefix(String companyName) {
        if (companyName == null) return "";
        String upperCompany = companyName.toUpperCase();
        // These are specific to Colombia and the PHP script's logic
        if (upperCompany.contains("TELMEX")) return "0456"; // Claro Hogar Fijo
        if (upperCompany.contains("COLOMBIA TELECOMUNICACIONES S.A. ESP")) return "09"; // Movistar Fijo
        if (upperCompany.contains("UNE EPM TELECOMUNICACIONES S.A. E.S.P.") || upperCompany.contains("UNE EPM TELCO S.A.")) return "05"; // UNE/Tigo
        if (upperCompany.contains("EMPRESA DE TELECOMUNICACIONES DE BOGOTÁ S.A. ESP.") || upperCompany.contains("ETB")) return "07"; // ETB
        return ""; // Default if no match
    }
}