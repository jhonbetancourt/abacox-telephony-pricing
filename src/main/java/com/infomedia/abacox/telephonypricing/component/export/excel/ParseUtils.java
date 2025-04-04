package com.infomedia.abacox.telephonypricing.component.export.excel;

import jakarta.validation.ValidationException;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ParseUtils {
    public static Map<String, String> parseAlternativeHeaders(String alternativeHeaders) {
        Map<String, String> headersMap = new HashMap<>();
        if (alternativeHeaders == null || alternativeHeaders.trim().isEmpty()) {
            return headersMap;
        }

        try {
            // Matches 'key':'value' pattern, handling spaces and escaped quotes
            Pattern pattern = Pattern.compile("'([^']+)':'([^']+)'");
            Matcher matcher = pattern.matcher(alternativeHeaders);

            while (matcher.find()) {
                String key = matcher.group(1).trim();
                String value = matcher.group(2).trim();
                if (!key.isEmpty() && !value.isEmpty()) {
                    headersMap.put(key, value);
                }
            }
        } catch (Exception e) {
            throw new ValidationException("Invalid alternative headers format, please use 'key1':'value1','key2':'value2',...");
        }

        return headersMap;
    }

    public static Set<String> parseExcludeColumns(String excludeColumns) {
        if (excludeColumns == null || excludeColumns.trim().isEmpty()) {
            return new HashSet<>();
        }

        // Regex pattern to match: 'text' followed by optional comma and whitespace
        Pattern pattern = Pattern.compile("'([^']*)'(?:\\s*,\\s*)?");
        Matcher matcher = pattern.matcher(excludeColumns);
        Set<String> result = new HashSet<>();

        boolean foundAny = false;
        while (matcher.find()) {
            foundAny = true;
            String column = matcher.group(1).trim();
            if (!column.isEmpty()) {
                result.add(column);
            }
        }

        if (!foundAny || !matcher.hitEnd()) {
            throw new ValidationException("Invalid exclude columns format, please use 'column1','column2',...");
        }

        return result;
    }
}
