package com.infomedia.abacox.telephonypricing.component.export.excel;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A utility class for generating Excel files from a list of entities.
 * <p>
 * This class uses reflection to create columns based on the fields of the entity.
 * It supports field exclusion (blacklist) or inclusion (whitelist), custom column naming,
 * header ordering, nested entities, value replacements, custom styling, and streaming.
 * <p>
 * Filtering is applied in stages:
 * <ol>
 *   <li><b>Field Path Filtering:</b> Use {@code withIncludedFields} (whitelist) OR {@code excludeField} (blacklist).</li>
 *   <li><b>Header Renaming:</b> Use {@code withAlternativeFieldName} and {@code withAlternativeHeaderName}.</li>
 *   <li><b>Final Column Name Filtering:</b> Use {@code withIncludedColumnNames} (whitelist) OR {@code excludeColumnByName} (blacklist).</li>
 * </ol>
 * <p>
 * Example usage:
 * <pre>{@code
 * GenericExcelGenerator.builder(myProductList)
 *      .withIncludedFields("productName", "price", "active")
 *      .withValueReplacement("Active", "TRUE", "Yes") // Replace by formatted value
 *      .withValueReplacement("Active", "false", "No") // Replace by raw value
 *      .generate("report.xlsx");
 * }</pre>
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class GenericExcelGenerator {

    private static final Logger LOGGER = Logger.getLogger(GenericExcelGenerator.class.getName());
    private static final Pattern CAMEL_CASE_SNAKE_CASE_SPLIT_PATTERN =
            Pattern.compile("(?<=[a-z])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])|_");

    public static <T> ExcelGeneratorBuilder<T> builder(List<T> entities) {
        return new ExcelGeneratorBuilder<>(entities);
    }

    public static class ExcelGeneratorBuilder<T> {
        private final List<T> entities;
        private Set<String> excludedFields = new HashSet<>();
        private Set<String> includedFields = null;
        private Map<String, String> alternativeFieldPathNames = new HashMap<>();
        private Map<String, String> alternativeHeaderNames = new HashMap<>();
        private List<String> alternativeHeaders;
        private Set<String> excludedColumnNames = new HashSet<>();
        private Set<String> includedColumnNames = null;
        private Map<String, Map<String, String>> valueReplacements = new HashMap<>();
        private boolean streamingEnabled = false;
        private String dateFormat = "yyyy-MM-dd";
        private String dateTimeFormat = "yyyy-MM-dd HH:mm:ss";
        private Consumer<CellStyle> headerStyleCustomizer;
        private Consumer<CellStyle> dataStyleCustomizer;

        private ExcelGeneratorBuilder(List<T> entities) {
            this.entities = Objects.requireNonNull(entities, "Entity list cannot be null.");
        }

        public ExcelGeneratorBuilder<T> withExcludedFields(Set<String> excludedFields) {
            this.excludedFields = Objects.requireNonNull(excludedFields);
            return this;
        }

        public ExcelGeneratorBuilder<T> excludeField(String fieldToExclude) {
            this.excludedFields.add(fieldToExclude);
            return this;
        }

        public ExcelGeneratorBuilder<T> withIncludedFields(String... includedFields) {
            this.includedFields = new HashSet<>(Arrays.asList(includedFields));
            return this;
        }

        public ExcelGeneratorBuilder<T> withIncludedFields(Set<String> includedFields) {
            this.includedFields = Objects.requireNonNull(includedFields);
            return this;
        }

        public ExcelGeneratorBuilder<T> withAlternativeFieldNames(Map<String, String> alternativeFieldPathNames) {
            this.alternativeFieldPathNames = Objects.requireNonNull(alternativeFieldPathNames);
            return this;
        }

        public ExcelGeneratorBuilder<T> withAlternativeFieldName(String fieldPath, String newName) {
            this.alternativeFieldPathNames.put(fieldPath, newName);
            return this;
        }

        public ExcelGeneratorBuilder<T> withAlternativeHeaderNames(Map<String, String> alternativeHeaderNames) {
            this.alternativeHeaderNames = Objects.requireNonNull(alternativeHeaderNames);
            return this;
        }

        public ExcelGeneratorBuilder<T> withAlternativeHeaderName(String originalHeader, String newName) {
            this.alternativeHeaderNames.put(originalHeader, newName);
            return this;
        }

        public ExcelGeneratorBuilder<T> withAlternativeHeaders(List<String> alternativeHeaders) {
            this.alternativeHeaders = alternativeHeaders;
            return this;
        }

        public ExcelGeneratorBuilder<T> withExcludedColumnNames(Set<String> excludedColumnNames) {
            this.excludedColumnNames = Objects.requireNonNull(excludedColumnNames);
            return this;
        }

        public ExcelGeneratorBuilder<T> excludeColumnByName(String columnName) {
            this.excludedColumnNames.add(columnName);
            return this;
        }

        public ExcelGeneratorBuilder<T> withIncludedColumnNames(String... includedColumnNames) {
            this.includedColumnNames = new HashSet<>(Arrays.asList(includedColumnNames));
            return this;
        }

        public ExcelGeneratorBuilder<T> withIncludedColumnNames(Set<String> includedColumnNames) {
            this.includedColumnNames = Objects.requireNonNull(includedColumnNames);
            return this;
        }

        public ExcelGeneratorBuilder<T> withValueReplacements(Map<String, Map<String, String>> valueReplacements) {
            this.valueReplacements = Objects.requireNonNull(valueReplacements);
            return this;
        }

        public ExcelGeneratorBuilder<T> withValueReplacement(String columnName, String originalValue, String replacementValue) {
            this.valueReplacements.computeIfAbsent(columnName, k -> new HashMap<>()).put(originalValue, replacementValue);
            return this;
        }

        public ExcelGeneratorBuilder<T> enableStreaming() {
            this.streamingEnabled = true;
            return this;
        }

        public ExcelGeneratorBuilder<T> withDateFormat(String dateFormat) {
            this.dateFormat = Objects.requireNonNull(dateFormat);
            return this;
        }

        public ExcelGeneratorBuilder<T> withDateTimeFormat(String dateTimeFormat) {
            this.dateTimeFormat = Objects.requireNonNull(dateTimeFormat);
            return this;
        }

        public ExcelGeneratorBuilder<T> withHeaderStyle(Consumer<CellStyle> headerStyleCustomizer) {
            this.headerStyleCustomizer = headerStyleCustomizer;
            return this;
        }

        public ExcelGeneratorBuilder<T> withDataStyle(Consumer<CellStyle> dataStyleCustomizer) {
            this.dataStyleCustomizer = dataStyleCustomizer;
            return this;
        }

        public void generate(String filePath) throws IOException {
            GenericExcelGenerator.generateExcel(this, filePath);
        }

        public InputStream generateAsInputStream() throws IOException {
            return GenericExcelGenerator.generateExcelInputStream(this);
        }
    }

    private static <T> InputStream generateExcelInputStream(ExcelGeneratorBuilder<T> builder) throws IOException {
        if (builder.includedFields != null && !builder.excludedFields.isEmpty()) {
            throw new IllegalStateException("Cannot use both withIncludedFields (whitelist) and excludeField (blacklist). Please choose one method for field-level filtering.");
        }
        if (builder.includedColumnNames != null && !builder.excludedColumnNames.isEmpty()) {
            throw new IllegalStateException("Cannot use both withIncludedColumnNames (whitelist) and excludeColumnByName (blacklist). Please choose one method for final column filtering.");
        }

        if (builder.entities == null || builder.entities.isEmpty()) {
            try (Workbook workbook = new XSSFWorkbook()) {
                workbook.createSheet("Empty");
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                workbook.write(outputStream);
                return new ByteArrayInputStream(outputStream.toByteArray());
            }
        }

        GeneratorContext<T> context = new GeneratorContext<>(builder);
        Workbook workbook = context.isStreamingEnabled() ? new SXSSFWorkbook(100) : new XSSFWorkbook();

        try {
            Class<?> entityClass = builder.entities.get(0).getClass();

            List<FieldInfo> fields = getFieldsInDeclarationOrder(entityClass, "", null,
                    context.getIncludedFields(), context.getExcludedFields(),
                    context.getAlternativeFieldPathNames(), new HashSet<>());

            if (!context.getAlternativeHeaderNames().isEmpty()) {
                fields.forEach(fieldInfo ->
                        fieldInfo.displayName = context.getAlternativeHeaderNames().getOrDefault(
                                fieldInfo.displayName, fieldInfo.displayName
                        ));
            }

            if (context.getIncludedColumnNames() != null) {
                final Set<String> finalIncludedNames = context.getIncludedColumnNames();
                fields = fields.stream()
                        .filter(fieldInfo -> finalIncludedNames.contains(fieldInfo.displayName))
                        .collect(Collectors.toList());
            } else if (!context.getExcludedColumnNames().isEmpty()) {
                final Set<String> finalExcludedNames = context.getExcludedColumnNames();
                fields = fields.stream()
                        .filter(fieldInfo -> !finalExcludedNames.contains(fieldInfo.displayName))
                        .collect(Collectors.toList());
            }

            fields.sort(Comparator.comparingInt(fi -> fi.order));

            Sheet sheet = workbook.createSheet(entityClass.getSimpleName());

            createHeaderRow(workbook, sheet, fields, context);
            createDataRows(sheet, builder.entities, fields, context);
            setColumnWidths(sheet, fields.size());

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            workbook.write(outputStream);
            return new ByteArrayInputStream(outputStream.toByteArray());

        } finally {
            if (workbook instanceof SXSSFWorkbook) {
                ((SXSSFWorkbook) workbook).dispose();
            }
            workbook.close();
        }
    }

    private static <T> void setFieldValue(Cell cell, T entity, FieldInfo fieldInfo, GeneratorContext<T> context) {
        try {
            Object rawValue = getFieldValue(entity, fieldInfo.fieldPath);

            if (rawValue == null) {
                cell.setBlank();
                return;
            }

            Map<String, String> replacementMap = context.getValueReplacements().get(fieldInfo.displayName);

            // --- Pass 1: Check for replacement using the RAW value's string representation (e.g., "true") ---
            if (replacementMap != null) {
                String rawValueAsString = (rawValue instanceof Enum) ? ((Enum<?>) rawValue).name() : String.valueOf(rawValue);
                if (replacementMap.containsKey(rawValueAsString)) {
                    cell.setCellValue(replacementMap.get(rawValueAsString));
                    return;
                }
            }

            // --- No raw match. Now, determine the final formatted string for the second-pass check ---
            String finalFormattedString;
            CellSetterContext setterContext = context.getCellSetterContext();

            if (rawValue instanceof Boolean) {
                finalFormattedString = String.valueOf(rawValue).toUpperCase();
            } else if (rawValue instanceof LocalDate) {
                finalFormattedString = ((LocalDate) rawValue).format(setterContext.dateFormatter);
            } else if (rawValue instanceof LocalDateTime) {
                finalFormattedString = ((LocalDateTime) rawValue).format(setterContext.dateTimeFormatter);
            } else if (rawValue instanceof Date) {
                finalFormattedString = setterContext.utilDateFormatter.format((Date) rawValue);
            } else if (rawValue instanceof Calendar) {
                finalFormattedString = setterContext.utilDateFormatter.format(((Calendar) rawValue).getTime());
            } else if (rawValue.getClass().isEnum()) {
                finalFormattedString = ((Enum<?>) rawValue).name();
            } else {
                finalFormattedString = String.valueOf(rawValue);
            }

            // --- Pass 2: Check for replacement using the FINAL FORMATTED string (e.g., "TRUE" or "2025-06-18") ---
            if (replacementMap != null && replacementMap.containsKey(finalFormattedString)) {
                cell.setCellValue(replacementMap.get(finalFormattedString));
                return;
            }

            // --- No replacements found. Write the value to the cell, using native types where possible. ---
            if (rawValue instanceof Number) {
                // Includes Integer, Long, Double, Float, Short, Byte, BigDecimal, BigInteger
                cell.setCellValue(((Number) rawValue).doubleValue());
            } else if (rawValue instanceof Boolean) {
                cell.setCellValue((Boolean) rawValue);
            } else {
                // For everything else (including our custom-formatted dates), set as a string.
                cell.setCellValue(finalFormattedString);
            }

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error setting value for field '" + fieldInfo.displayName + "' on entity " + entity.getClass().getSimpleName(), e);
            cell.setCellValue("!ERROR");
        }
    }

    private static <T> void generateExcel(ExcelGeneratorBuilder<T> builder, String filePath) throws IOException {
        try (InputStream in = generateExcelInputStream(builder);
             OutputStream out = new FileOutputStream(filePath)) {
            in.transferTo(out);
        }
    }

    private static class GeneratorContext<T> {
        private final ExcelGeneratorBuilder<T> builder;
        private final CellSetterContext cellSetterContext;

        GeneratorContext(ExcelGeneratorBuilder<T> builder) {
            this.builder = builder;
            this.cellSetterContext = new CellSetterContext(
                    DateTimeFormatter.ofPattern(builder.dateFormat),
                    DateTimeFormatter.ofPattern(builder.dateTimeFormat),
                    new SimpleDateFormat(builder.dateTimeFormat)
            );
        }
        public Set<String> getIncludedFields() { return builder.includedFields; }
        public Set<String> getExcludedFields() { return builder.excludedFields; }
        public Map<String, String> getAlternativeFieldPathNames() { return builder.alternativeFieldPathNames; }
        public Map<String, String> getAlternativeHeaderNames() { return builder.alternativeHeaderNames; }
        public List<String> getAlternativeHeaders() { return builder.alternativeHeaders; }
        public Set<String> getIncludedColumnNames() { return builder.includedColumnNames; }
        public Set<String> getExcludedColumnNames() { return builder.excludedColumnNames; }
        public Map<String, Map<String, String>> getValueReplacements() { return builder.valueReplacements; }
        public boolean isStreamingEnabled() { return builder.streamingEnabled; }
        public Consumer<CellStyle> getHeaderStyleCustomizer() { return builder.headerStyleCustomizer; }
        public Consumer<CellStyle> getDataStyleCustomizer() { return builder.dataStyleCustomizer; }
        public CellSetterContext getCellSetterContext() { return cellSetterContext; }
    }

    private static class CellSetterContext {
        final DateTimeFormatter dateFormatter;
        final DateTimeFormatter dateTimeFormatter;
        final SimpleDateFormat utilDateFormatter;

        CellSetterContext(DateTimeFormatter dateFormatter, DateTimeFormatter dateTimeFormatter, SimpleDateFormat utilDateFormatter) {
            this.dateFormatter = dateFormatter;
            this.dateTimeFormatter = dateTimeFormatter;
            this.utilDateFormatter = utilDateFormatter;
        }
    }

    private static final Set<Class<?>> SIMPLE_TYPES = new HashSet<>(Arrays.asList(String.class, Boolean.class, boolean.class, Integer.class, int.class, Long.class, long.class, Double.class, double.class, Float.class, float.class, Short.class, short.class, Byte.class, byte.class, Character.class, char.class, LocalDate.class, LocalDateTime.class, UUID.class, BigDecimal.class, BigInteger.class, java.util.Date.class, java.util.Calendar.class));

    private static class FieldInfo {
        Field field;
        String displayName;
        Field[] fieldPath;
        int order;

        FieldInfo(Field field, String displayName, Field[] fieldPath, int order) {
            this.field = field;
            this.displayName = displayName;
            this.fieldPath = fieldPath;
            this.order = order;
        }
    }

    private static List<FieldInfo> getFieldsInDeclarationOrder(Class<?> clazz, String prefix, Field[] parentPath,
                                                               Set<String> includedFields, Set<String> excludedFields,
                                                               Map<String, String> alternativeFieldPathNames,
                                                               Set<Class<?>> visitedInPath) {
        if (visitedInPath.contains(clazz)) {
            LOGGER.log(Level.WARNING, "Circular reference detected for class {0}. Skipping further recursion.", clazz.getName());
            return Collections.emptyList();
        }
        visitedInPath.add(clazz);

        List<FieldInfo> collectedFields = new ArrayList<>();
        Deque<Class<?>> classHierarchy = new ArrayDeque<>();
        Class<?> currentClass = clazz;
        while (currentClass != null && currentClass != Object.class) {
            classHierarchy.addFirst(currentClass);
            currentClass = currentClass.getSuperclass();
        }

        for (Class<?> processingClass : classHierarchy) {
            for (Field field : processingClass.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) continue;

                String fullPath = buildFieldPath(parentPath, field);

                boolean isWhitelistActive = includedFields != null;
                if (isWhitelistActive) {
                    boolean isIncluded = includedFields.stream().anyMatch(p -> p.equals(fullPath) || p.startsWith(fullPath + "."));
                    if (!isIncluded) {
                        continue;
                    }
                } else if (excludedFields.contains(fullPath)) {
                    continue;
                }

                ExcelColumn excelColumn = field.getAnnotation(ExcelColumn.class);
                if (excelColumn != null && excelColumn.ignore()) continue;

                field.setAccessible(true);
                int fieldOrder = (excelColumn != null) ? excelColumn.order() : Integer.MAX_VALUE;

                if (isSimpleType(field.getType())) {
                    if (isWhitelistActive && !includedFields.contains(fullPath)) {
                        continue;
                    }
                    String displayName;
                    if (alternativeFieldPathNames.containsKey(fullPath)) {
                        displayName = alternativeFieldPathNames.get(fullPath);
                    } else {
                        String fieldBaseName = (excelColumn != null && !excelColumn.name().isEmpty()) ? excelColumn.name() : formatFieldName(field.getName());
                        displayName = prefix + fieldBaseName;
                    }
                    Field[] fieldPath = appendToPath(parentPath, field);
                    collectedFields.add(new FieldInfo(field, displayName, fieldPath, fieldOrder));

                } else if (isJpaEntity(field.getType()) && !Collection.class.isAssignableFrom(field.getType())) {
                    String fieldBaseName = (excelColumn != null && !excelColumn.name().isEmpty()) ? excelColumn.name() : formatFieldName(field.getName());
                    String newPrefix = prefix + fieldBaseName + " - ";
                    Field[] newPath = appendToPath(parentPath, field);
                    collectedFields.addAll(getFieldsInDeclarationOrder(field.getType(), newPrefix, newPath, includedFields, excludedFields, alternativeFieldPathNames, visitedInPath));
                }
            }
        }

        visitedInPath.remove(clazz);
        return collectedFields;
    }

    private static String buildFieldPath(Field[] parentPath, Field currentField) {
        StringBuilder path = new StringBuilder();
        if (parentPath != null) {
            for (Field field : parentPath) {
                if (path.length() > 0) path.append(".");
                path.append(field.getName());
            }
        }
        if (path.length() > 0) path.append(".");
        path.append(currentField.getName());
        return path.toString();
    }

    private static Field[] appendToPath(Field[] currentPath, Field newField) {
        if (currentPath == null || currentPath.length == 0) return new Field[]{newField};
        Field[] newPath = Arrays.copyOf(currentPath, currentPath.length + 1);
        newPath[currentPath.length] = newField;
        return newPath;
    }

    private static <T> void createHeaderRow(Workbook workbook, Sheet sheet, List<FieldInfo> fields, GeneratorContext<T> context) {
        Row headerRow = sheet.createRow(0);
        CellStyle headerStyle = createHeaderStyle(workbook, context.getHeaderStyleCustomizer());
        List<String> alternativeHeaders = context.getAlternativeHeaders();
        for (int i = 0; i < fields.size(); i++) {
            Cell cell = headerRow.createCell(i);
            String headerValue = (alternativeHeaders != null && i < alternativeHeaders.size() && alternativeHeaders.get(i) != null)
                    ? alternativeHeaders.get(i)
                    : fields.get(i).displayName;
            cell.setCellValue(headerValue);
            cell.setCellStyle(headerStyle);
        }
    }

    private static CellStyle createHeaderStyle(Workbook workbook, Consumer<CellStyle> styleCustomizer) {
        CellStyle headerStyle = workbook.createCellStyle();
        Font headerFont = workbook.createFont();
        headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        headerFont.setBold(true);
        headerStyle.setFont(headerFont);
        headerStyle.setBorderBottom(BorderStyle.THIN);
        headerStyle.setBorderTop(BorderStyle.THIN);
        headerStyle.setBorderLeft(BorderStyle.THIN);
        headerStyle.setBorderRight(BorderStyle.THIN);
        if (styleCustomizer != null) {
            styleCustomizer.accept(headerStyle);
        }
        return headerStyle;
    }

    private static <T> void createDataRows(Sheet sheet, List<T> entities, List<FieldInfo> fields, GeneratorContext<T> context) {
        int rowNum = 1;
        CellStyle dataStyle = createDataStyle(sheet.getWorkbook(), context.getDataStyleCustomizer());
        for (T entity : entities) {
            Row row = sheet.createRow(rowNum++);
            for (int i = 0; i < fields.size(); i++) {
                Cell cell = row.createCell(i);
                cell.setCellStyle(dataStyle);
                setFieldValue(cell, entity, fields.get(i), context);
            }
        }
    }

    private static CellStyle createDataStyle(Workbook workbook, Consumer<CellStyle> styleCustomizer) {
        CellStyle dataStyle = workbook.createCellStyle();
        dataStyle.setBorderBottom(BorderStyle.THIN);
        dataStyle.setBorderTop(BorderStyle.THIN);
        dataStyle.setBorderLeft(BorderStyle.THIN);
        dataStyle.setBorderRight(BorderStyle.THIN);
        if (styleCustomizer != null) {
            styleCustomizer.accept(dataStyle);
        }
        return dataStyle;
    }

    private static void setColumnWidths(Sheet sheet, int columnCount) {
        for (int i = 0; i < columnCount; i++) {
            sheet.autoSizeColumn(i);
            int currentWidth = sheet.getColumnWidth(i);
            int maxWidth = 256 * 50;
            int minWidth = 256 * 10;
            if (currentWidth > maxWidth) {
                sheet.setColumnWidth(i, maxWidth);
            } else if (currentWidth < minWidth) {
                sheet.setColumnWidth(i, minWidth);
            } else {
                sheet.setColumnWidth(i, currentWidth + 256);
            }
        }
    }

    private static Object getFieldValue(Object entity, Field[] fieldPath) throws IllegalAccessException {
        Object currentObject = entity;
        for (Field field : fieldPath) {
            if (currentObject == null) return null;
            field.setAccessible(true);
            currentObject = field.get(currentObject);
        }
        return currentObject;
    }

    private static boolean isSimpleType(Class<?> type) {
        return SIMPLE_TYPES.contains(type) || type.isEnum();
    }

    private static boolean isJpaEntity(Class<?> clazz) {
        if (clazz.isAnnotationPresent(jakarta.persistence.Entity.class)) return true;
        try {
            Class<?> javaxEntityAnnotation = Class.forName("javax.persistence.Entity");
            return clazz.isAnnotationPresent((Class<? extends java.lang.annotation.Annotation>) javaxEntityAnnotation);
        } catch (ClassNotFoundException | ClassCastException e) {
            return false;
        }
    }

    private static String formatFieldName(String fieldName) {
        if (fieldName == null || fieldName.isEmpty()) return "";
        String[] words = CAMEL_CASE_SNAKE_CASE_SPLIT_PATTERN.split(fieldName);
        return Arrays.stream(words)
                .filter(word -> !word.isEmpty())
                .map(word -> Character.toUpperCase(word.charAt(0)) + word.substring(1).toLowerCase())
                .collect(Collectors.joining(" "));
    }

    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    @java.lang.annotation.Target(java.lang.annotation.ElementType.FIELD)
    public @interface ExcelColumn {
        String name() default "";
        boolean ignore() default false;
        int order() default Integer.MAX_VALUE;
    }
}