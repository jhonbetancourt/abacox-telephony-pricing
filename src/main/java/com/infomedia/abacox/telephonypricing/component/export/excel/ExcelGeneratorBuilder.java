package com.infomedia.abacox.telephonypricing.component.export.excel;

import org.apache.poi.ss.usermodel.CellStyle;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.function.Consumer;

/**
 * A builder for configuring and creating an Excel file via {@link GenericExcelGenerator}.
 * <p>
 * This class collects all configuration options, such as the data source,
 * field inclusions/exclusions, header renaming, and styling.
 * <p>
 * Obtain an instance of this builder by calling {@link GenericExcelGenerator#builder()}.
 */
public class ExcelGeneratorBuilder {
    List<?> entities;
    Set<String> excludedFields = new HashSet<>();
    Set<String> includedFields = null;
    Map<String, String> alternativeFieldPathNames = new HashMap<>();
    Map<String, String> alternativeHeaderNames = new HashMap<>();
    List<String> alternativeHeaders;
    Set<String> excludedColumnNames = new HashSet<>();
    Set<String> includedColumnNames = null;
    Map<String, Map<String, String>> valueReplacements = new HashMap<>();
    boolean streamingEnabled = false;
    String dateFormat = "yyyy-MM-dd";
    String dateTimeFormat = "yyyy-MM-dd HH:mm:ss";
    Consumer<CellStyle> headerStyleCustomizer;
    Consumer<CellStyle> dataStyleCustomizer;

    /**
     * Package-private constructor. Instances should be created via GenericExcelGenerator.builder().
     */
    ExcelGeneratorBuilder() {
        this.entities = Collections.emptyList();
    }

    /**
     * Sets the list of entities to be exported to the Excel file.
     * This is a mandatory step before generating the file.
     *
     * @param entities The list of objects to export. Cannot be null.
     * @return this builder instance for chaining.
     */
    public ExcelGeneratorBuilder withEntities(List<?> entities) {
        this.entities = Objects.requireNonNull(entities, "Entity list cannot be null.");
        return this;
    }

    public ExcelGeneratorBuilder withExcludedFields(Set<String> excludedFields) {
        this.excludedFields = Objects.requireNonNull(excludedFields);
        return this;
    }

    public ExcelGeneratorBuilder excludeField(String fieldToExclude) {
        this.excludedFields.add(fieldToExclude);
        return this;
    }

    public ExcelGeneratorBuilder withIncludedFields(String... includedFields) {
        this.includedFields = new HashSet<>(Arrays.asList(includedFields));
        return this;
    }

    public ExcelGeneratorBuilder withIncludedFields(Set<String> includedFields) {
        this.includedFields = Objects.requireNonNull(includedFields);
        return this;
    }

    public ExcelGeneratorBuilder withAlternativeFieldNames(Map<String, String> alternativeFieldPathNames) {
        this.alternativeFieldPathNames = Objects.requireNonNull(alternativeFieldPathNames);
        return this;
    }

    public ExcelGeneratorBuilder withAlternativeFieldName(String fieldPath, String newName) {
        this.alternativeFieldPathNames.put(fieldPath, newName);
        return this;
    }

    public ExcelGeneratorBuilder withAlternativeHeaderNames(Map<String, String> alternativeHeaderNames) {
        this.alternativeHeaderNames = Objects.requireNonNull(alternativeHeaderNames);
        return this;
    }

    public ExcelGeneratorBuilder withAlternativeHeaderName(String originalHeader, String newName) {
        this.alternativeHeaderNames.put(originalHeader, newName);
        return this;
    }

    public ExcelGeneratorBuilder withAlternativeHeaders(List<String> alternativeHeaders) {
        this.alternativeHeaders = alternativeHeaders;
        return this;
    }

    public ExcelGeneratorBuilder withExcludedColumnNames(Set<String> excludedColumnNames) {
        this.excludedColumnNames = Objects.requireNonNull(excludedColumnNames);
        return this;
    }

    public ExcelGeneratorBuilder excludeColumnByName(String columnName) {
        this.excludedColumnNames.add(columnName);
        return this;
    }

    public ExcelGeneratorBuilder withIncludedColumnNames(String... includedColumnNames) {
        this.includedColumnNames = new HashSet<>(Arrays.asList(includedColumnNames));
        return this;
    }

    public ExcelGeneratorBuilder withIncludedColumnNames(Set<String> includedColumnNames) {
        this.includedColumnNames = Objects.requireNonNull(includedColumnNames);
        return this;
    }

    public ExcelGeneratorBuilder withValueReplacements(Map<String, Map<String, String>> valueReplacements) {
        this.valueReplacements = Objects.requireNonNull(valueReplacements);
        return this;
    }

    public ExcelGeneratorBuilder withValueReplacement(String columnName, String originalValue, String replacementValue) {
        this.valueReplacements.computeIfAbsent(columnName, k -> new HashMap<>()).put(originalValue, replacementValue);
        return this;
    }

    public ExcelGeneratorBuilder enableStreaming() {
        this.streamingEnabled = true;
        return this;
    }

    public ExcelGeneratorBuilder withDateFormat(String dateFormat) {
        this.dateFormat = Objects.requireNonNull(dateFormat);
        return this;
    }

    public ExcelGeneratorBuilder withDateTimeFormat(String dateTimeFormat) {
        this.dateTimeFormat = Objects.requireNonNull(dateTimeFormat);
        return this;
    }

    public ExcelGeneratorBuilder withHeaderStyle(Consumer<CellStyle> headerStyleCustomizer) {
        this.headerStyleCustomizer = headerStyleCustomizer;
        return this;
    }

    public ExcelGeneratorBuilder withDataStyle(Consumer<CellStyle> dataStyleCustomizer) {
        this.dataStyleCustomizer = dataStyleCustomizer;
        return this;
    }

    /**
     * Generates the Excel file and saves it to the specified path.
     *
     * @param filePath The path where the Excel file will be saved.
     * @throws IOException if an I/O error occurs during file writing.
     */
    public void generate(String filePath) throws IOException {
        GenericExcelGenerator.generateExcel(this, filePath);
    }

    /**
     * Generates the Excel file and returns it as an {@link InputStream}.
     *
     * @return An InputStream containing the generated Excel file data.
     * @throws IOException if an I/O error occurs during generation.
     */
    public InputStream generateAsInputStream() throws IOException {
        return GenericExcelGenerator.generateExcelInputStream(this);
    }
}