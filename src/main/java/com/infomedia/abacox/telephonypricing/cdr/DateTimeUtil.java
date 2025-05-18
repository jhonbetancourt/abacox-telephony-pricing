package com.infomedia.abacox.telephonypricing.cdr;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class DateTimeUtil {

    // Example: 11:43:53.825 CO Tue Sep 11 2007
    // Note: Cisco times are UTC.
    private static final List<DateTimeFormatter> CME_DATE_FORMATTERS = Arrays.asList(
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS zz E MMM dd yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS z E MMM dd yyyy", Locale.ENGLISH) // Some logs might have single char timezone
    );


    public static LocalDateTime epochSecondsToLocalDateTime(long epochSeconds) {
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), ZoneOffset.UTC);
    }

    public static long localDateTimeToEpochSeconds(LocalDateTime ldt) {
        return ldt.toEpochSecond(ZoneOffset.UTC);
    }

    public static LocalDateTime parseCmeDateTime(String cmeDateTimeString) {
        if (cmeDateTimeString == null || cmeDateTimeString.trim().isEmpty()) {
            return null;
        }
        // Cisco CM 6.0 CDRs use epoch seconds for dateTimeOrigination, etc.
        // This CME specific parser is not directly used for CM 6.0 but kept for reference from PHP.
        for (DateTimeFormatter formatter : CME_DATE_FORMATTERS) {
            try {
                // The zone "CO" is not standard. We might need to preprocess or use a custom resolver.
                // For simplicity, if it's a known non-standard one, replace or handle.
                // Or, assume it's a variant of a standard one if parsing fails.
                // A more robust solution would involve mapping these non-standard zones.
                String parsableString = cmeDateTimeString.replace(" CO ", " UTC "); // Assuming CO is a placeholder for a UTC-like zone
                return LocalDateTime.parse(parsableString, formatter);
            } catch (DateTimeParseException e) {
                // Try next formatter
            }
        }
        // Fallback for epoch seconds if other parsing fails (though CM 6.0 should be epoch)
        try {
            long epochSeconds = Long.parseLong(cmeDateTimeString);
            return epochSecondsToLocalDateTime(epochSeconds);
        } catch (NumberFormatException nfe) {
            // log.warn("Could not parse CME date-time string: {}", cmeDateTimeString, e);
        }
        return null;
    }

    public static LocalDateTime stringToLocalDateTime(String dateTimeStr) {
        if (dateTimeStr == null || dateTimeStr.isEmpty()) return null;
        try {
            // Try ISO_LOCAL_DATE_TIME first
            return LocalDateTime.parse(dateTimeStr, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (DateTimeParseException e1) {
            try {
                // Try with space separator
                return LocalDateTime.parse(dateTimeStr, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            } catch (DateTimeParseException e2) {
                // Try epoch seconds
                try {
                    long epochSeconds = Long.parseLong(dateTimeStr);
                    return epochSecondsToLocalDateTime(epochSeconds);
                } catch (NumberFormatException e3) {
                    // log.warn("Failed to parse date-time string: {}", dateTimeStr);
                    return null;
                }
            }
        }
    }
}
