// File: com/infomedia/abacox/telephonypricing/cdr/DateTimeUtil.java
package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId; // Added
import java.time.ZoneOffset;
import java.time.ZonedDateTime; // Added
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import lombok.extern.log4j.Log4j2; // Added

@Log4j2 // Added
public class DateTimeUtil {

    private static final List<DateTimeFormatter> CME_DATE_FORMATTERS = Arrays.asList(
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS zz E MMM dd yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS z E MMM dd yyyy", Locale.ENGLISH)
    );


    public static LocalDateTime epochSecondsToLocalDateTime(long epochSeconds) {
        // Converts the absolute UTC epoch into the pinned local timezone
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), ZoneId.systemDefault());
    }

    public static long localDateTimeToEpochSeconds(LocalDateTime ldt) {
        if (ldt == null) return 0L;
        // Converts the local time back to an absolute epoch
        return ldt.atZone(ZoneId.systemDefault()).toEpochSecond();
    }

    public static LocalDateTime parseCmeDateTime(String cmeDateTimeString) {
        if (cmeDateTimeString == null || cmeDateTimeString.trim().isEmpty()) {
            return null;
        }
        for (DateTimeFormatter formatter : CME_DATE_FORMATTERS) {
            try {
                String parsableString = cmeDateTimeString.replace(" CO ", " UTC ");
                return LocalDateTime.parse(parsableString, formatter);
            } catch (DateTimeParseException e) {
                // Try next formatter
            }
        }
        try {
            long epochSeconds = Long.parseLong(cmeDateTimeString);
            return epochSecondsToLocalDateTime(epochSeconds);
        } catch (NumberFormatException nfe) {
            log.debug("Could not parse CME date-time string: {}", cmeDateTimeString);
        }
        return null;
    }

    public static LocalDateTime stringToLocalDateTime(String dateTimeStr) {
        if (dateTimeStr == null || dateTimeStr.isEmpty()) return null;
        try {
            return LocalDateTime.parse(dateTimeStr, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (DateTimeParseException e1) {
            try {
                return LocalDateTime.parse(dateTimeStr, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            } catch (DateTimeParseException e2) {
                try {
                    long epochSeconds = Long.parseLong(dateTimeStr);
                    return epochSecondsToLocalDateTime(epochSeconds);
                } catch (NumberFormatException e3) {
                    log.debug("Failed to parse date-time string: {}", dateTimeStr);
                    return null;
                }
            }
        }
    }

    // New method for timezone conversion
    public static LocalDateTime convertToZone(LocalDateTime utcDateTime, ZoneId targetZoneId) {
        if (utcDateTime == null || targetZoneId == null) {
            return utcDateTime; // Or handle error appropriately
        }
        ZonedDateTime zdtUtc = utcDateTime.atZone(ZoneOffset.UTC);
        ZonedDateTime zdtTarget = zdtUtc.withZoneSameInstant(targetZoneId);
        return zdtTarget.toLocalDateTime();
    }
}