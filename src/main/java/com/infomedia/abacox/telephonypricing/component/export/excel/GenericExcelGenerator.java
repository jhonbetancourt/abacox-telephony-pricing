package com.infomedia.abacox.telephonypricing.component.export.excel;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
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
 * This class uses reflection to create columns based on the fields of the
 * entity.
 * It supports field exclusion (blacklist) or inclusion (whitelist), custom
 * column naming,
 * header ordering, nested entities, value replacements, custom styling, and
 * streaming.
 * <p>
 * Filtering is applied in stages:
 * <ol>
 * <li><b>Field Path Filtering:</b> Use {@code withIncludedFields} (whitelist)
 * OR {@code excludeField} (blacklist).</li>
 * <li><b>Header Renaming:</b> Use {@code withAlternativeFieldName} and
 * {@code withAlternativeHeaderName}.</li>
 * <li><b>Final Column Name Filtering:</b> Use {@code withIncludedColumnNames}
 * (whitelist) OR {@code excludeColumnByName} (blacklist).</li>
 * </ol>
 * <p>
 * Example usage:
 * 
 * <pre>{@code
 * GenericExcelGenerator.builder()
 *         .withEntities(myProductList)
 *         .withIncludedFields("productName", "price", "active")
 *         .withValueReplacement("Active", "TRUE", "Yes") // Replace by formatted value
 *         .withValueReplacement("Active", "false", "No") // Replace by raw value
 *         .generate("report.xlsx");
 * }</pre>
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class GenericExcelGenerator {

    private static final Logger LOGGER = Logger.getLogger(GenericExcelGenerator.class.getName());
    private static final Pattern CAMEL_CASE_SNAKE_CASE_SPLIT_PATTERN = Pattern
            .compile("(?<=[a-z])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])|_");

    /**
     * Creates a new builder instance for configuring and generating an Excel file.
     *
     * @return a new {@link ExcelGeneratorBuilder} instance.
     */
    public static ExcelGeneratorBuilder builder() {
        return new ExcelGeneratorBuilder();
    }

    /**
     * Package-private method to generate the Excel file as an InputStream.
     * Called by the {@link ExcelGeneratorBuilder}.
     */
    static InputStream generateExcelInputStream(ExcelGeneratorBuilder builder) throws IOException {
        if (builder.includedFields != null && !builder.excludedFields.isEmpty()) {
            throw new IllegalStateException(
                    "Cannot use both withIncludedFields (whitelist) and excludeField (blacklist). Please choose one method for field-level filtering.");
        }
        if (builder.includedColumnNames != null && !builder.excludedColumnNames.isEmpty()) {
            throw new IllegalStateException(
                    "Cannot use both withIncludedColumnNames (whitelist) and excludeColumnByName (blacklist). Please choose one method for final column filtering.");
        }
        if (builder.flattenedCollectionFieldName != null
                && builder.collectionsAsStringFields.containsKey(builder.flattenedCollectionFieldName)) {
            throw new IllegalStateException("Field '" + builder.flattenedCollectionFieldName
                    + "' cannot be both flattened and formatted as a single string.");
        }

        if (builder.entities == null || builder.entities.isEmpty()) {
            try (Workbook workbook = new XSSFWorkbook()) {
                workbook.createSheet("Empty");
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                workbook.write(outputStream);
                return new ByteArrayInputStream(outputStream.toByteArray());
            }
        }

        GeneratorContext context = new GeneratorContext(builder);
        Workbook workbook = context.isStreamingEnabled() ? new SXSSFWorkbook(100) : new XSSFWorkbook();

        try {
            Class<?> entityClass = builder.entities.get(0).getClass();

            List<FieldInfo> fields;
            if (context.getFlattenedCollectionFieldName() != null) {
                fields = getFieldsForFlattening(entityClass, context);
            } else {
                fields = getFieldsInDeclarationOrder(entityClass, "", null, context, new HashSet<>());
            }

            if (!context.getAlternativeHeaderNames().isEmpty()) {
                fields.forEach(fieldInfo -> fieldInfo.displayName = context.getAlternativeHeaderNames().getOrDefault(
                        fieldInfo.displayName, fieldInfo.displayName));
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

            if (context.getFlattenedCollectionFieldName() != null) {
                createFlattenedDataRows(sheet, builder.entities, fields, context);
            } else {
                createDataRows(sheet, builder.entities, fields, context);
            }
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

    /**
     * Package-private method to generate the Excel file and save it to a path.
     * Called by the {@link ExcelGeneratorBuilder}.
     */
    static void generateExcel(ExcelGeneratorBuilder builder, String filePath) throws IOException {
        try (InputStream in = generateExcelInputStream(builder);
                OutputStream out = new FileOutputStream(filePath)) {
            in.transferTo(out);
        }
    }

    private static void setFieldValue(Cell cell, Object entity, FieldInfo fieldInfo, GeneratorContext context) {
        try {
            Object rawValue = getFieldValue(entity, fieldInfo.fieldPath);

            if (rawValue == null) {
                cell.setBlank();
                return;
            }

            if (fieldInfo.collectionAsStringAttributes != null) {
                if (rawValue instanceof Collection) {
                    Collection<?> collection = (Collection<?>) rawValue;
                    String formatted = collection.stream()
                            .map(item -> formatCollectionItem(item, fieldInfo.collectionAsStringAttributes))
                            .collect(Collectors.joining("\n"));
                    cell.setCellValue(formatted);
                } else {
                    cell.setCellValue(String.valueOf(rawValue));
                }
                return;
            }

            Map<String, String> replacementMap = context.getValueReplacements().get(fieldInfo.displayName);

            // --- Pass 1: Check for replacement using the RAW value's string representation
            // (e.g., "true") ---
            if (replacementMap != null) {
                String rawValueAsString = (rawValue instanceof Enum) ? ((Enum<?>) rawValue).name()
                        : String.valueOf(rawValue);
                if (replacementMap.containsKey(rawValueAsString)) {
                    cell.setCellValue(replacementMap.get(rawValueAsString));
                    return;
                }
            }

            // --- No raw match. Now, determine the final formatted string for the
            // second-pass check ---
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

            // --- Pass 2: Check for replacement using the FINAL FORMATTED string (e.g.,
            // "TRUE" or "2025-06-18") ---
            if (replacementMap != null && replacementMap.containsKey(finalFormattedString)) {
                cell.setCellValue(replacementMap.get(finalFormattedString));
                return;
            }

            // --- No replacements found. Write the value to the cell, using native types
            // where possible. ---
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
            LOGGER.log(Level.SEVERE, "Error setting value for field '" + fieldInfo.displayName + "' on entity "
                    + entity.getClass().getSimpleName(), e);
            cell.setCellValue("!ERROR");
        }
    }

    private static class GeneratorContext {
        private final ExcelGeneratorBuilder builder;
        private final CellSetterContext cellSetterContext;

        GeneratorContext(ExcelGeneratorBuilder builder) {
            this.builder = builder;
            this.cellSetterContext = new CellSetterContext(
                    DateTimeFormatter.ofPattern(builder.dateFormat),
                    DateTimeFormatter.ofPattern(builder.dateTimeFormat),
                    new SimpleDateFormat(builder.dateTimeFormat));
        }

        public Set<String> getIncludedFields() {
            return builder.includedFields;
        }

        public Set<String> getExcludedFields() {
            return builder.excludedFields;
        }

        public Map<String, String> getAlternativeFieldPathNames() {
            return builder.alternativeFieldPathNames;
        }

        public Map<String, String> getAlternativeHeaderNames() {
            return builder.alternativeHeaderNames;
        }

        public List<String> getAlternativeHeaders() {
            return builder.alternativeHeaders;
        }

        public Set<String> getIncludedColumnNames() {
            return builder.includedColumnNames;
        }

        public Set<String> getExcludedColumnNames() {
            return builder.excludedColumnNames;
        }

        public Map<String, Map<String, String>> getValueReplacements() {
            return builder.valueReplacements;
        }

        public boolean isStreamingEnabled() {
            return builder.streamingEnabled;
        }

        public Consumer<CellStyle> getHeaderStyleCustomizer() {
            return builder.headerStyleCustomizer;
        }

        public Consumer<CellStyle> getDataStyleCustomizer() {
            return builder.dataStyleCustomizer;
        }

        public CellSetterContext getCellSetterContext() {
            return cellSetterContext;
        }

        public Map<String, List<String>> getCollectionsAsStringFields() {
            return builder.collectionsAsStringFields;
        }

        public String getFlattenedCollectionFieldName() {
            return builder.flattenedCollectionFieldName;
        }
    }

    private static class CellSetterContext {
        final DateTimeFormatter dateFormatter;
        final DateTimeFormatter dateTimeFormatter;
        final SimpleDateFormat utilDateFormatter;

        CellSetterContext(DateTimeFormatter dateFormatter, DateTimeFormatter dateTimeFormatter,
                SimpleDateFormat utilDateFormatter) {
            this.dateFormatter = dateFormatter;
            this.dateTimeFormatter = dateTimeFormatter;
            this.utilDateFormatter = utilDateFormatter;
        }
    }

    private static final Set<Class<?>> SIMPLE_TYPES = new HashSet<>(
            Arrays.asList(String.class, Boolean.class, boolean.class, Integer.class, int.class, Long.class, long.class,
                    Double.class, double.class, Float.class, float.class, Short.class, short.class, Byte.class,
                    byte.class, Character.class, char.class, LocalDate.class, LocalDateTime.class, UUID.class,
                    BigDecimal.class, BigInteger.class, java.util.Date.class, java.util.Calendar.class));

    private static class FieldInfo {
        Field field;
        String displayName;
        Field[] fieldPath;
        int order;
        boolean isFlattenedChildField;
        List<String> collectionAsStringAttributes;

        FieldInfo(Field field, String displayName, Field[] fieldPath, int order) {
            this.field = field;
            this.displayName = displayName;
            this.fieldPath = fieldPath;
            this.order = order;
        }
    }

    private static List<FieldInfo> getFieldsInDeclarationOrder(Class<?> clazz, String prefix, Field[] parentPath,
            GeneratorContext context, Set<Class<?>> visitedInPath) {
        if (visitedInPath.contains(clazz)) {
            LOGGER.log(Level.WARNING, "Circular reference detected for class {0}. Skipping further recursion.",
                    clazz.getName());
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
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic())
                    continue;

                String fullPath = buildFieldPath(parentPath, field);

                boolean isWhitelistActive = context.getIncludedFields() != null;
                if (isWhitelistActive) {
                    boolean isIncluded = context.getIncludedFields().stream()
                            .anyMatch(p -> p.equals(fullPath) || p.startsWith(fullPath + "."));
                    if (!isIncluded) {
                        continue;
                    }
                } else if (context.getExcludedFields().contains(fullPath)) {
                    continue;
                }

                ExcelColumn excelColumn = field.getAnnotation(ExcelColumn.class);
                if (excelColumn != null && excelColumn.ignore())
                    continue;

                field.setAccessible(true);
                int fieldOrder = (excelColumn != null) ? excelColumn.order() : Integer.MAX_VALUE;

                boolean isCollectionAsString = context.getCollectionsAsStringFields().containsKey(fullPath);

                if (isSimpleType(field.getType()) || isCollectionAsString) {
                    if (isWhitelistActive && !context.getIncludedFields().contains(fullPath)) {
                        continue;
                    }
                    String displayName;
                    if (context.getAlternativeFieldPathNames().containsKey(fullPath)) {
                        displayName = context.getAlternativeFieldPathNames().get(fullPath);
                    } else {
                        String fieldBaseName = (excelColumn != null && !excelColumn.name().isEmpty())
                                ? excelColumn.name()
                                : formatFieldName(field.getName());
                        displayName = prefix + fieldBaseName;
                    }
                    Field[] fieldPath = appendToPath(parentPath, field);
                    FieldInfo fieldInfo = new FieldInfo(field, displayName, fieldPath, fieldOrder);
                    if (isCollectionAsString) {
                        fieldInfo.collectionAsStringAttributes = context.getCollectionsAsStringFields().get(fullPath);
                    }
                    collectedFields.add(fieldInfo);

                } else if (isJpaEntity(field.getType()) && !Collection.class.isAssignableFrom(field.getType())) {
                    String fieldBaseName = (excelColumn != null && !excelColumn.name().isEmpty()) ? excelColumn.name()
                            : formatFieldName(field.getName());
                    String newPrefix = prefix + fieldBaseName + " - ";
                    Field[] newPath = appendToPath(parentPath, field);
                    collectedFields.addAll(getFieldsInDeclarationOrder(field.getType(), newPrefix, newPath,
                            context, visitedInPath));
                }
            }
        }

        visitedInPath.remove(clazz);
        return collectedFields;
    }

    private static List<FieldInfo> getFieldsForFlattening(Class<?> rootClass, GeneratorContext context) {
        // 1. Get Root fields
        List<FieldInfo> rootFields = getFieldsInDeclarationOrder(rootClass, "", null, context, new HashSet<>());

        // 2. Find collection field to determine Child class
        String collectionFieldName = context.getFlattenedCollectionFieldName();
        Field collectionField = findField(rootClass, collectionFieldName);
        if (collectionField == null) {
            throw new IllegalArgumentException("Flattened collection field '" + collectionFieldName
                    + "' not found in class " + rootClass.getName());
        }
        if (!Collection.class.isAssignableFrom(collectionField.getType())) {
            throw new IllegalArgumentException("Field '" + collectionFieldName + "' is not a Collection.");
        }

        // 3. Determine Child class from generic type
        Class<?> childClass;
        try {
            ParameterizedType pt = (ParameterizedType) collectionField.getGenericType();
            childClass = (Class<?>) pt.getActualTypeArguments()[0];
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Could not determine generic type for collection field '" + collectionFieldName + "'.", e);
        }

        // 4. Get Child fields
        // Use a prefix for child fields to avoid collision and improve clarity.
        // We use the collection field name as prefix base.
        String childPrefix = formatFieldName(collectionFieldName) + " - ";
        List<FieldInfo> childFields = getFieldsInDeclarationOrder(childClass, childPrefix, null, context,
                new HashSet<>());

        // Mark child fields
        childFields.forEach(f -> f.isFlattenedChildField = true);

        // Combine
        List<FieldInfo> allFields = new ArrayList<>(rootFields);
        allFields.addAll(childFields);
        return allFields;
    }

    private static Field findField(Class<?> clazz, String fieldName) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private static void createFlattenedDataRows(Sheet sheet, List<?> entities, List<FieldInfo> fields,
            GeneratorContext context) {
        int rowNum = 1;
        CellStyle dataStyle = createDataStyle(sheet.getWorkbook(), context.getDataStyleCustomizer());
        CellStyle wrappedDataStyle = createDataStyle(sheet.getWorkbook(), context.getDataStyleCustomizer());
        wrappedDataStyle.setWrapText(true);
        String collectionFieldName = context.getFlattenedCollectionFieldName();
        // pre-fetch collection field
        Field collectionField;
        if (!entities.isEmpty()) {
            collectionField = findField(entities.get(0).getClass(), collectionFieldName);
            if (collectionField != null)
                collectionField.setAccessible(true);
        } else {
            return;
        }

        for (Object rootEntity : entities) {
            Collection<?> children = null;
            try {
                if (collectionField != null) {
                    children = (Collection<?>) collectionField.get(rootEntity);
                }
            } catch (IllegalAccessException e) {
                LOGGER.warning("Could not access flattened collection field: " + e.getMessage());
            }

            if (children == null || children.isEmpty()) {
                // Option 1: Skip root if no children?
                // Option 2: Print root with empty child columns?
                // Usually for "flatten" usage, we want at least one row.
                // Let's print one row with null child.
                createSingleFlattenedRow(sheet, rowNum++, rootEntity, null, fields, context, dataStyle,
                        wrappedDataStyle);
            } else {
                for (Object childEntity : children) {
                    createSingleFlattenedRow(sheet, rowNum++, rootEntity, childEntity, fields, context, dataStyle,
                            wrappedDataStyle);
                }
            }
        }
    }

    private static void createSingleFlattenedRow(Sheet sheet, int rowNum, Object root, Object child,
            List<FieldInfo> fields, GeneratorContext context, CellStyle dataStyle, CellStyle wrappedDataStyle) {
        Row row = sheet.createRow(rowNum);
        for (int i = 0; i < fields.size(); i++) {
            Cell cell = row.createCell(i);
            FieldInfo fieldInfo = fields.get(i);
            cell.setCellStyle(fieldInfo.collectionAsStringAttributes != null ? wrappedDataStyle : dataStyle);
            Object targetEntity = fieldInfo.isFlattenedChildField ? child : root;
            if (targetEntity != null) {
                setFieldValue(cell, targetEntity, fieldInfo, context);
            } else {
                cell.setBlank();
            }
        }
    }

    private static String buildFieldPath(Field[] parentPath, Field currentField) {
        StringBuilder path = new StringBuilder();
        if (parentPath != null) {
            for (Field field : parentPath) {
                if (path.length() > 0)
                    path.append(".");
                path.append(field.getName());
            }
        }
        if (path.length() > 0)
            path.append(".");
        path.append(currentField.getName());
        return path.toString();
    }

    private static Field[] appendToPath(Field[] currentPath, Field newField) {
        if (currentPath == null || currentPath.length == 0)
            return new Field[] { newField };
        Field[] newPath = Arrays.copyOf(currentPath, currentPath.length + 1);
        newPath[currentPath.length] = newField;
        return newPath;
    }

    private static void createHeaderRow(Workbook workbook, Sheet sheet, List<FieldInfo> fields,
            GeneratorContext context) {
        Row headerRow = sheet.createRow(0);
        CellStyle headerStyle = createHeaderStyle(workbook, context.getHeaderStyleCustomizer());
        List<String> alternativeHeaders = context.getAlternativeHeaders();
        for (int i = 0; i < fields.size(); i++) {
            Cell cell = headerRow.createCell(i);
            String headerValue = (alternativeHeaders != null && i < alternativeHeaders.size()
                    && alternativeHeaders.get(i) != null)
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

    private static void createDataRows(Sheet sheet, List<?> entities, List<FieldInfo> fields,
            GeneratorContext context) {
        int rowNum = 1;
        CellStyle dataStyle = createDataStyle(sheet.getWorkbook(), context.getDataStyleCustomizer());
        CellStyle wrappedDataStyle = createDataStyle(sheet.getWorkbook(), context.getDataStyleCustomizer());
        wrappedDataStyle.setWrapText(true);
        for (Object entity : entities) {
            Row row = sheet.createRow(rowNum++);
            for (int i = 0; i < fields.size(); i++) {
                Cell cell = row.createCell(i);
                FieldInfo fieldInfo = fields.get(i);
                cell.setCellStyle(fieldInfo.collectionAsStringAttributes != null ? wrappedDataStyle : dataStyle);
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
            if (currentObject == null)
                return null;
            field.setAccessible(true);
            currentObject = field.get(currentObject);
        }
        return currentObject;
    }

    private static boolean isSimpleType(Class<?> type) {
        return SIMPLE_TYPES.contains(type) || type.isEnum();
    }

    private static boolean isJpaEntity(Class<?> clazz) {
        if (clazz.isAnnotationPresent(jakarta.persistence.Entity.class))
            return true;
        try {
            Class<?> javaxEntityAnnotation = Class.forName("javax.persistence.Entity");
            return clazz.isAnnotationPresent((Class<? extends java.lang.annotation.Annotation>) javaxEntityAnnotation);
        } catch (ClassNotFoundException | ClassCastException e) {
            return false;
        }
    }

    private static String formatCollectionItem(Object item, List<String> attributes) {
        if (item == null)
            return "";
        if (attributes == null || attributes.isEmpty()) {
            attributes = new ArrayList<>();
            Class<?> clazz = item.getClass();
            while (clazz != null && clazz != Object.class) {
                for (Field f : clazz.getDeclaredFields()) {
                    if (!Modifier.isStatic(f.getModifiers()) && !f.isSynthetic()) {
                        attributes.add(f.getName());
                    }
                }
                clazz = clazz.getSuperclass();
            }
        }
        return attributes.stream()
                .map(attrName -> {
                    Object val = getFieldValueByName(item, attrName);
                    if (val == null || (val instanceof String && ((String) val).isEmpty())) {
                        return null;
                    }
                    return formatFieldName(attrName) + ": " + val;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.joining(", "));
    }

    private static Object getFieldValueByName(Object item, String fieldName) {
        Field field = findField(item.getClass(), fieldName);
        if (field != null) {
            field.setAccessible(true);
            try {
                return field.get(item);
            } catch (IllegalAccessException e) {
                return null;
            }
        }
        return null;
    }

    private static String formatFieldName(String fieldName) {
        if (fieldName == null || fieldName.isEmpty())
            return "";
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