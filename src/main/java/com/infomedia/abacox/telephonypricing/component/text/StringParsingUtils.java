package com.infomedia.abacox.telephonypricing.component.text;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A utility class for parsing specific string formats into Java collections.
 * Supports both single (') and double (") quotes, including mixed usage.
 */
public final class StringParsingUtils {

    /**
     * Regex for: 'key'.'key':'value' OR "key"."key":"value"
     * Groups: 2=outerKey, 4=innerKey, 6=value
     */
    private static final Pattern NESTED_MAP_PATTERN = Pattern.compile(
        "(['\"])(.+?)\\1\\.(['\"])(.+?)\\3:(['\"])(.+?)\\5"
    );

    /**
     * Regex for: 'key':'value' OR "key":"value"
     * It uses backreferences to ensure quotes match for each part.
     *
     * Breakdown:
     * (['"])      - Group 1: Captures the opening quote for the key.
     * (.+?)        - Group 2: Captures the key's content.
     * \\1          - Backreference: Matches the key's closing quote.
     * :            - Matches the literal colon.
     * (['"])      - Group 3: Captures the opening quote for the value.
     * (.+?)        - Group 4: Captures the value's content.
     * \\3          - Backreference: Matches the value's closing quote.
     */
    private static final Pattern SIMPLE_MAP_PATTERN = Pattern.compile(
        "(['\"])(.+?)\\1:(['\"])(.+?)\\3"
    );

    /**
     * Regex for: 'value' OR "value"
     * Groups: 2=value
     */
    private static final Pattern SET_PATTERN = Pattern.compile("(['\"])(.+?)\\1");


    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private StringParsingUtils() {}

    // --- PUBLIC METHODS ---

    /**
     * Parses a string like "'key1'.'key11':'value11',"key2"."key21":"value21"'
     * into a nested Map structure. Handles both single and double quotes.
     *
     * @param input The string to parse.
     * @return A Map<String, Map<String, String>> representing the parsed data.
     */
    public static Map<String, Map<String, String>> parseNestedMap(String input) {
        Map<String, Map<String, String>> resultMap = new HashMap<>();
        if (input == null || input.trim().isEmpty()) {
            return resultMap;
        }
        Matcher matcher = NESTED_MAP_PATTERN.matcher(input);
        while (matcher.find()) {
            resultMap.computeIfAbsent(matcher.group(2), k -> new HashMap<>())
                     .put(matcher.group(4), matcher.group(6));
        }
        return resultMap;
    }

    /**
     * Parses a string of key-value pairs like "'key1':'value1',\"key2\":\"value2\""
     * into a simple Map. Handles both single and double quotes.
     *
     * @param input The string to parse.
     * @return A Map<String, String> representing the parsed data.
     */
    public static Map<String, String> parseSimpleMap(String input) {
        Map<String, String> resultMap = new HashMap<>();
        if (input == null || input.trim().isEmpty()) {
            return resultMap;
        }
        Matcher matcher = SIMPLE_MAP_PATTERN.matcher(input);
        while (matcher.find()) {
            // Group 2 is the key, Group 4 is the value
            String key = matcher.group(2);
            String value = matcher.group(4);
            resultMap.put(key, value);
        }
        return resultMap;
    }

    /**
     * Parses a string of quoted, comma-separated values like "'value1',"value2",'value3'"
     * into a Set of strings. Handles both single and double quotes.
     *
     * @param input The string to parse.
     * @return A Set<String> containing the extracted values.
     */
    public static Set<String> parseToSet(String input) {
        Set<String> resultSet = new HashSet<>();
        if (input == null || input.trim().isEmpty()) {
            return resultSet;
        }
        Matcher matcher = SET_PATTERN.matcher(input);
        while (matcher.find()) {
            resultSet.add(matcher.group(2));
        }
        return resultSet;
    }
}