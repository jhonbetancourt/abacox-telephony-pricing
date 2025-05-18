package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.CommunicationLocation;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

@Service
@Log4j2
@RequiredArgsConstructor
public class PhoneNumberTransformationService {

    private final IndicatorLookupService indicatorLookupService; // For _esCelular_fijo's DB lookup part

    public static class TransformationResult {
        private final String transformedNumber;
        private final boolean transformed;
        private final Long newTelephonyTypeId;

        public TransformationResult(String transformedNumber, boolean transformed, Long newTelephonyTypeId) {
            this.transformedNumber = transformedNumber;
            this.transformed = transformed;
            this.newTelephonyTypeId = newTelephonyTypeId;
        }
        public String getTransformedNumber() { return transformedNumber; }
        public boolean isTransformed() { return transformed; }
        public Long getNewTelephonyTypeId() { return newTelephonyTypeId; }
    }

    /**
     * Transforms an incoming phone number based on Colombian numbering plan rules.
     * Mimics PHP's _esEntrante_60.
     */
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
                if ("604".equals(p3)) { // Example, specific area code
                    phoneNumber = phoneNumber.substring(len - 8);
                } else if ("03".equals(p2)) { // Cellular with leading 0
                    String n3_digits = phoneNumber.substring(1, 4);
                    try {
                        int n3val = Integer.parseInt(n3_digits);
                        if (n3val >= 300 && n3val <= 350) { // Colombian mobile prefixes
                            phoneNumber = phoneNumber.substring(len - 10);
                            newTelephonyTypeId = TelephonyTypeEnum.CELLULAR.getValue();
                        }
                    } catch (NumberFormatException | StringIndexOutOfBoundsException ignored) {}
                }
            } else if (len == 10) {
                if ("60".equals(p2) || "57".equals(p2)) { // National prefix
                    phoneNumber = phoneNumber.substring(len - 8);
                } else if (phoneNumber.startsWith("3")) { // Potentially cellular without 03
                    try {
                        int n3val = Integer.parseInt(phoneNumber.substring(0, 3));
                        if (n3val >= 300 && n3val <= 350) {
                            newTelephonyTypeId = TelephonyTypeEnum.CELLULAR.getValue();
                        }
                    } catch (NumberFormatException | StringIndexOutOfBoundsException ignored) {}
                }
            } else if (len == 9) { // National with 9 digits
                if ("60".equals(p2)) {
                    phoneNumber = phoneNumber.substring(len - 7);
                }
            }
        }
        return new TransformationResult(phoneNumber, !phoneNumber.equals(originalPhoneNumber), newTelephonyTypeId);
    }

    /**
     * Transforms an outgoing phone number based on Colombian numbering plan rules.
     * Mimics PHP's _es_Saliente.
     */
    public TransformationResult transformOutgoingNumber(String phoneNumber, Long originCountryId) {
        if (phoneNumber == null) return new TransformationResult(null, false, null);
        String originalPhoneNumber = phoneNumber;
        // Long newTelephonyTypeId = null; // _es_Saliente in PHP doesn't set telephony type

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

    /**
     * Transforms a number for prefix lookup, specific to Colombian plan.
     * Mimics PHP's _esCelular_fijo.
     */
    public TransformationResult transformForPrefixLookup(String phoneNumber, CommunicationLocation commLocation) {
        if (phoneNumber == null || commLocation == null || commLocation.getIndicator() == null) {
            return new TransformationResult(phoneNumber, false, null);
        }
        String originalPhoneNumber = phoneNumber;
        Long newTelephonyTypeId = null; // _esCelular_fijo doesn't directly set this, but implies it

        if (commLocation.getIndicator().getOriginCountryId() != null && commLocation.getIndicator().getOriginCountryId() == 1L) { // Colombia
            int len = phoneNumber.length();
            if (len == 10) {
                if (phoneNumber.startsWith("3")) { // Cellular
                    phoneNumber = "03" + phoneNumber; // Add "03" prefix for cellular lookup
                    newTelephonyTypeId = TelephonyTypeEnum.CELLULAR.getValue(); // Implied
                } else if (phoneNumber.startsWith("60")) { // New fixed line format
                    String ndcPart = phoneNumber.substring(2, 3); // e.g., "1" for Bogota from "601"
                    String subscriberPart = phoneNumber.substring(3);

                    // PHP's _esCelular_fijo has a DB lookup here to determine if it's truly local
                    // or should be treated as national. This is complex.
                    // SELECT INDICATIVO_DPTO_PAIS, INDICATIVO_CIUDAD, SERIE_EMPRESA FROM serie, indicativo WHERE ...
                    // For now, a simplified approach: if it matches current location's NDC, treat as local.
                    String localNdc = indicatorLookupService.findLocalNdcForIndicator(commLocation.getIndicatorId());
                    if (ndcPart.equals(localNdc)) {
                        phoneNumber = subscriberPart; // Treat as local
                        newTelephonyTypeId = TelephonyTypeEnum.LOCAL.getValue();
                    } else {
                        // It's national, PHP prepends an operator code like "09"
                        // This requires knowing the "default" national operator prefix.
                        // For simplicity, we'll just use the "60" + NDC part.
                        // Or, more closely to PHP, it might become "09" + original number without "60"
                        // phoneNumber = "09" + phoneNumber.substring(2); // Example for "09" operator
                        // The PHP logic is: $numero = substr($numero, 2); $g_numero = $numero = $ind.$numero;
                        // where $ind is like '09', '05', '07' based on SERIE_EMPRESA.
                        // This is too complex without the SERIE_EMPRESA mapping.
                        // Let's assume it remains as "60..." for national prefix matching.
                        // Or, if we want to match PHP's intent of stripping "60" and then letting prefix matching find "09", "05" etc.:
                        phoneNumber = phoneNumber.substring(2); // Remove "60"
                        newTelephonyTypeId = TelephonyTypeEnum.NATIONAL.getValue();
                    }
                }
            }
        }
        return new TransformationResult(phoneNumber, !phoneNumber.equals(originalPhoneNumber), newTelephonyTypeId);
    }
}