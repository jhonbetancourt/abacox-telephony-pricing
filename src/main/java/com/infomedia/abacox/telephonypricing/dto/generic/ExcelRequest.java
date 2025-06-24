package com.infomedia.abacox.telephonypricing.dto.generic;

import com.infomedia.abacox.telephonypricing.component.export.excel.ExcelGeneratorBuilder;
import com.infomedia.abacox.telephonypricing.component.export.excel.GenericExcelGenerator;
import com.infomedia.abacox.telephonypricing.component.text.StringParsingUtils;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springdoc.core.annotations.ParameterObject;

import java.util.Map;
import java.util.Set;

/**
 * Parameter object for customizing report output
 */
@Data
@ParameterObject
public class ExcelRequest {

    @Schema(description = "Alternative headers mapping (format: 'key':'value' or \"key\":\"value\")")
    private String alternativeHeaders;

    @Schema(description = "Columns to exclude (comma-separated, quoted values)")
    private String excludeColumns;

    @Schema(description = "Columns to include (comma-separated, quoted values)")
    private String includeColumns;

    @Schema(description = "Value replacements in format 'column'.'oldValue':'newValue'")
    private String valueReplacements;

    /**
     * Parses alternativeHeaders string into a Map using StringParsingUtils
     * @return Map of original column names to alternative names
     */
    public Map<String, String> getAlternativeHeadersMap() {
        return StringParsingUtils.parseSimpleMap(alternativeHeaders);
    }

    /**
     * Parses excludeColumns string into a Set using StringParsingUtils
     * @return Set of column names to exclude
     */
    public Set<String> getExcludeColumnsSet() {
        return StringParsingUtils.parseToSet(excludeColumns);
    }

    /**
     * Parses includeColumns string into a Set using StringParsingUtils
     * @return Set of column names to include
     */
    public Set<String> getIncludeColumnsSet() {
        return StringParsingUtils.parseToSet(includeColumns);
    }

    /**
     * Parses valueReplacements string into a nested Map using StringParsingUtils
     * @return Map of column names to Maps of old values to new values
     */
    public Map<String, Map<String, String>> getValueReplacementsMap() {
        return StringParsingUtils.parseNestedMap(valueReplacements);
    }

    public ExcelGeneratorBuilder toExcelGeneratorBuilder(){
        Map<String, String> alternativeHeadersMap = StringParsingUtils.parseSimpleMap(alternativeHeaders);
        Set<String> excludeColumnsList = StringParsingUtils.parseToSet(excludeColumns);
        Set<String> includeColumnsList = StringParsingUtils.parseToSet(includeColumns);
        Map<String, Map<String, String>> valueReplacementsMap = StringParsingUtils.parseNestedMap(valueReplacements);

        ExcelGeneratorBuilder builder = GenericExcelGenerator.builder();
        if (alternativeHeaders != null) builder.withAlternativeHeaderNames(alternativeHeadersMap);
        if (excludeColumns != null) builder.withExcludedColumnNames(excludeColumnsList);
        if (includeColumns != null) builder.withIncludedColumnNames(includeColumnsList);
        if (valueReplacements != null) builder.withValueReplacements(valueReplacementsMap);
        return builder;
    }
}