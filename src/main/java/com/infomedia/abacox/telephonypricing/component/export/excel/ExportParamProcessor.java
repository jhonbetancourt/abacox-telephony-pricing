package com.infomedia.abacox.telephonypricing.component.export.excel;

import com.fasterxml.jackson.core.type.TypeReference;
import com.infomedia.abacox.telephonypricing.component.modeltools.ModelConverter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class ExportParamProcessor {

    private final ModelConverter modelConverter;

    public ExcelGeneratorBuilder base64ParamsToExcelGeneratorBuilder(String alternativeHeaders
            , String excludeColumns, String includeColumns, String valueReplacements) {
        Map<String, String> alternativeHeadersMap = modelConverter.convert(alternativeHeaders==null?null:
                new String(Base64.getDecoder().decode(alternativeHeaders)), new TypeReference<Map<String, String>>() {});
        Set<String> excludeColumnsList = modelConverter.convert(excludeColumns==null?null:
                new String(Base64.getDecoder().decode(excludeColumns)), new TypeReference<Set<String>>() {});
        Set<String> includeColumnsList = modelConverter.convert(includeColumns==null?null:
                new String(Base64.getDecoder().decode(includeColumns)), new TypeReference<Set<String>>() {});
        Map<String, Map<String, String>> valueReplacementsMap = modelConverter.convert(valueReplacements==null?null:
                new String(Base64.getDecoder().decode(valueReplacements)), new TypeReference<Map<String, Map<String, String>>>() {});

        ExcelGeneratorBuilder builder = GenericExcelGenerator.builder();
        if (alternativeHeaders != null) builder.withAlternativeHeaderNames(alternativeHeadersMap);
        if (excludeColumns != null) builder.withExcludedColumnNames(excludeColumnsList);
        if (includeColumns != null) builder.withIncludedColumnNames(includeColumnsList);
        if (valueReplacements != null) builder.withValueReplacements(valueReplacementsMap);
        return builder;
    }
}
