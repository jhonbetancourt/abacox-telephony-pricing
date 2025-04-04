package com.infomedia.abacox.telephonypricing.component.export.excel;

import jakarta.persistence.Entity; // Assuming JPA for isJpaEntity check
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
// import org.slf4j.Logger; // Optional: For logging errors
// import org.slf4j.LoggerFactory; // Optional: For logging errors

import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier; // Added for checking static fields
import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

public class GenericExcelGenerator {

    // Optional: Logger for errors during field access/formatting
    // private static final Logger logger = LoggerFactory.getLogger(GenericExcelGenerator.class);

    private static final Set<Class<?>> SIMPLE_TYPES = new HashSet<>(Arrays.asList(
            String.class,
            Boolean.class, boolean.class,
            Integer.class, int.class,
            Long.class, long.class,
            Double.class, double.class,
            Float.class, float.class,
            Short.class, short.class,
            Byte.class, byte.class,
            Character.class, char.class,
            LocalDate.class,
            LocalDateTime.class,
            UUID.class,
            BigDecimal.class,
            BigInteger.class,
            java.util.Date.class,
            java.util.Calendar.class
    ));

    private static class FieldInfo {
        Field field;
        String displayName;
        Field[] fieldPath;
        int order; // Store the annotation order

        FieldInfo(Field field, String displayName, Field[] fieldPath, int order) {
            this.field = field;
            this.displayName = displayName;
            this.fieldPath = fieldPath;
            this.order = order;
        }
    }

    // --- Overloaded methods remain the same ---
    public static <T> InputStream generateExcelInputStream(List<T> entities) throws IOException {
        return generateExcelInputStream(entities, Collections.emptySet(), Collections.emptyMap(), null, Collections.emptySet());
    }

    public static <T> InputStream generateExcelInputStream(List<T> entities, Set<String> excludedFields) throws IOException {
        return generateExcelInputStream(entities, excludedFields, Collections.emptyMap(), null, Collections.emptySet());
    }

    public static <T> InputStream generateExcelInputStream(List<T> entities, Set<String> excludedFields, List<String> alternativeHeaders) throws IOException {
        return generateExcelInputStream(entities, excludedFields, Collections.emptyMap(), alternativeHeaders, Collections.emptySet());
    }

    public static <T> InputStream generateExcelInputStream(List<T> entities, Map<String, String> alternativeNames) throws IOException {
        return generateExcelInputStream(entities, Collections.emptySet(), alternativeNames, null, Collections.emptySet());
    }

    public static <T> InputStream generateExcelInputStream(List<T> entities, Map<String, String> alternativeNames, Set<String> excludedColumnNames) throws IOException {
        return generateExcelInputStream(entities, Collections.emptySet(), alternativeNames, null, excludedColumnNames);
    }

    public static <T> InputStream generateExcelInputStream(List<T> entities, List<String> alternativeHeaders) throws IOException {
        return generateExcelInputStream(entities, Collections.emptySet(), Collections.emptyMap(), alternativeHeaders, Collections.emptySet());
    }

    public static <T> InputStream generateExcelInputStream(List<T> entities, Set<String> excludedFields,
                                                           Map<String, String> alternativeNames) throws IOException {
        return generateExcelInputStream(entities, excludedFields, alternativeNames, null, Collections.emptySet());
    }

    public static <T> InputStream generateExcelInputStreamExcludeColumns(List<T> entities, Set<String> excludedColumnNames) throws IOException {
        return generateExcelInputStream(entities, Collections.emptySet(), Collections.emptyMap(), null, excludedColumnNames);
    }

    public static <T> InputStream generateExcelInputStream(List<T> entities, Set<String> excludedFields,
                                                           Map<String, String> alternativeNames, List<String> alternativeHeaders) throws IOException {
        return generateExcelInputStream(entities, excludedFields, alternativeNames, alternativeHeaders, Collections.emptySet());
    }

    public static <T> InputStream generateExcelInputStream(List<T> entities, Set<String> excludedFields,
                                                           Map<String, String> alternativeNames,
                                                           Set<String> excludedColumnNames) throws IOException {
        return generateExcelInputStream(entities, excludedFields, alternativeNames, null, excludedColumnNames);
    }


    public static <T> InputStream generateExcelInputStream(List<T> entities, Set<String> excludedFields,
                                                           Map<String, String> alternativeNames, List<String> alternativeHeaders,
                                                           Set<String> excludedColumnNames) throws IOException {
        if (entities == null || entities.isEmpty()) {
             Workbook workbook = new XSSFWorkbook();
             workbook.createSheet("Empty");
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
             workbook.write(outputStream);
             workbook.close();
             return new ByteArrayInputStream(outputStream.toByteArray());
        }

        Class<?> entityClass = entities.get(0).getClass();
        // 1. Get fields in declaration order (honoring hierarchy)
        List<FieldInfo> fields = getFieldsInDeclarationOrder(entityClass, "", null,
                excludedFields != null ? excludedFields : Collections.emptySet(),
                alternativeNames != null ? alternativeNames : Collections.emptyMap());

        // 2. Filter out fields whose display names are in excludedColumnNames
        if (excludedColumnNames != null && !excludedColumnNames.isEmpty()) {
            fields = fields.stream()
                    .filter(fieldInfo -> !excludedColumnNames.contains(fieldInfo.displayName))
                    .collect(Collectors.toList());
        }

        // 3. Apply stable sort based *only* on the explicit order annotation
        // Fields with the same order value will maintain their relative declaration order.
        fields.sort(Comparator.comparingInt(fi -> fi.order));

        // --- Rest of the method remains the same ---
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet(entityClass.getSimpleName());

        createHeaderRow(workbook, sheet, fields, alternativeHeaders);
        createDataRows(sheet, entities, fields);
        setColumnWidths(sheet, fields.size());

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        workbook.write(outputStream);
        workbook.close();

        return new ByteArrayInputStream(outputStream.toByteArray());
    }

    // --- Overloaded generateExcel methods remain the same ---
    public static <T> void generateExcel(List<T> entities, String filePath) throws IOException {
        generateExcel(entities, filePath, Collections.emptySet(), Collections.emptyMap(), null, Collections.emptySet());
    }

    public static <T> void generateExcel(List<T> entities, String filePath, Set<String> excludedFields) throws IOException {
        generateExcel(entities, filePath, excludedFields, Collections.emptyMap(), null, Collections.emptySet());
    }

    public static <T> void generateExcel(List<T> entities, String filePath, Map<String, String> alternativeNames) throws IOException {
        generateExcel(entities, filePath, Collections.emptySet(), alternativeNames, null, Collections.emptySet());
    }

    public static <T> void generateExcel(List<T> entities, String filePath, List<String> alternativeHeaders) throws IOException {
        generateExcel(entities, filePath, Collections.emptySet(), Collections.emptyMap(), alternativeHeaders, Collections.emptySet());
    }

    public static <T> void generateExcel(List<T> entities, String filePath, Set<String> excludedFields,
                                         Map<String, String> alternativeNames) throws IOException {
        generateExcel(entities, filePath, excludedFields, alternativeNames, null, Collections.emptySet());
    }

    public static <T> void generateExcelExcludeColumns(List<T> entities, String filePath,
                                                       Set<String> excludedColumnNames) throws IOException {
        generateExcel(entities, filePath, Collections.emptySet(), Collections.emptyMap(), null, excludedColumnNames);
    }

    public static <T> void generateExcel(List<T> entities, String filePath, Set<String> excludedFields,
                                         Map<String, String> alternativeNames, List<String> alternativeHeaders) throws IOException {
        generateExcel(entities, filePath, excludedFields, alternativeNames, alternativeHeaders, Collections.emptySet());
    }


    public static <T> void generateExcel(List<T> entities, String filePath, Set<String> excludedFields,
                                         Map<String, String> alternativeNames, List<String> alternativeHeaders,
                                         Set<String> excludedColumnNames) throws IOException {
         if (entities == null || entities.isEmpty()) {
            try (Workbook workbook = new XSSFWorkbook()) {
                workbook.createSheet("Empty");
                 try (FileOutputStream fileOut = new FileOutputStream(filePath)) {
                    workbook.write(fileOut);
                }
            }
            return;
        }

        Class<?> entityClass = entities.get(0).getClass();
        // 1. Get fields in declaration order (honoring hierarchy)
        List<FieldInfo> fields = getFieldsInDeclarationOrder(entityClass, "", null,
                excludedFields != null ? excludedFields : Collections.emptySet(),
                alternativeNames != null ? alternativeNames : Collections.emptyMap());

        // 2. Filter out fields whose display names are in excludedColumnNames
        if (excludedColumnNames != null && !excludedColumnNames.isEmpty()) {
            fields = fields.stream()
                    .filter(fieldInfo -> !excludedColumnNames.contains(fieldInfo.displayName))
                    .collect(Collectors.toList());
        }

        // 3. Apply stable sort based *only* on the explicit order annotation
        fields.sort(Comparator.comparingInt(fi -> fi.order));

        // --- Rest of the method remains the same ---
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet(entityClass.getSimpleName());

            createHeaderRow(workbook, sheet, fields, alternativeHeaders);
            createDataRows(sheet, entities, fields);
            setColumnWidths(sheet, fields.size());

            try (FileOutputStream fileOut = new FileOutputStream(filePath)) {
                workbook.write(fileOut);
            }
        }
    }

    /**
     * Recursively collects FieldInfo objects for a given class and its superclasses,
     * maintaining declaration order within each class level. Superclass fields come first.
     *
     * @param clazz            The class to analyze.
     * @param prefix           The prefix for display names (used for nested entities).
     * @param parentPath       The path of fields leading to this class (used for nested entities and exclusions).
     * @param excludedFields   Set of full field paths to exclude.
     * @param alternativeNames Map for overriding display names.
     * @return A list of FieldInfo objects in declaration order (superclass fields first).
     */
     private static List<FieldInfo> getFieldsInDeclarationOrder(Class<?> clazz, String prefix, Field[] parentPath,
                                                       Set<String> excludedFields, Map<String, String> alternativeNames) {
        List<FieldInfo> collectedFields = new ArrayList<>();

        // Use a Deque to process superclasses first, then the current class
        Deque<Class<?>> classHierarchy = new ArrayDeque<>();
        Class<?> currentClass = clazz;
        while (currentClass != null && currentClass != Object.class) {
            classHierarchy.addFirst(currentClass); // Add superclasses to the front
            currentClass = currentClass.getSuperclass();
        }

        // Process classes from top superclass down to the specific subclass
        for (Class<?> processingClass : classHierarchy) {
            Field[] declaredFields = processingClass.getDeclaredFields();
            for (Field field : declaredFields) {
                // Skip static fields, synthetic fields (like jacoco coverage adds)
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }

                if (shouldSkipField(field, parentPath, excludedFields)) {
                    continue;
                }

                field.setAccessible(true); // Ensure private fields are accessible

                ExcelColumn excelColumn = field.getAnnotation(ExcelColumn.class);
                String fieldBaseName = (excelColumn != null && !excelColumn.name().isEmpty())
                        ? excelColumn.name()
                        : formatFieldName(field.getName());
                int fieldOrder = (excelColumn != null) ? excelColumn.order() : Integer.MAX_VALUE;


                if (isSimpleType(field.getType())) {
                    String originalDisplayName = prefix + fieldBaseName;
                    String displayName = alternativeNames.getOrDefault(originalDisplayName, originalDisplayName);
                    Field[] fieldPath = appendToPath(parentPath, field);
                    collectedFields.add(new FieldInfo(field, displayName, fieldPath, fieldOrder));
                } else if (isJpaEntity(field.getType()) && !Collection.class.isAssignableFrom(field.getType())) {
                    // Recursive call for nested JPA entities (excluding collections)
                    String newPrefix = prefix + fieldBaseName + " - ";
                    Field[] newPath = appendToPath(parentPath, field);
                    // Recursively get fields for the nested type, preserving their order
                    collectedFields.addAll(getFieldsInDeclarationOrder(field.getType(), newPrefix, newPath, excludedFields, alternativeNames));
                }
                // Note: Non-simple, non-JPA-entity, non-collection fields are currently ignored.
            }
        }

        return collectedFields;
    }

    // --- Helper methods (shouldSkipField, buildFieldPath, appendToPath, createHeaderRow, createHeaderStyle, createDataRows, setColumnWidths, setFieldValue, getFieldValue, isSimpleType, isJpaEntity, formatFieldName) remain largely the same as in the previous version ---
    // Minor update: FieldInfo constructor now includes 'order'
    // Minor update: getFieldsInDeclarationOrder now calculates and passes 'fieldOrder' to FieldInfo

    private static boolean shouldSkipField(Field field, Field[] parentPath, Set<String> excludedFields) {
        if ("serialVersionUID".equals(field.getName())) {
            return true;
        }
        ExcelColumn excelColumn = field.getAnnotation(ExcelColumn.class);
        if (excelColumn != null && excelColumn.ignore()) {
            return true;
        }
        String fullPath = buildFieldPath(parentPath, field);
        return excludedFields.contains(fullPath);
    }

    private static String buildFieldPath(Field[] parentPath, Field currentField) {
        StringBuilder path = new StringBuilder();
        if (parentPath != null) {
            for (Field field : parentPath) {
                 if (path.length() > 0) {
                    path.append(".");
                }
                path.append(field.getName());
            }
        }
         if (path.length() > 0) {
             path.append(".");
         }
        path.append(currentField.getName());
        return path.toString();
    }


    private static Field[] appendToPath(Field[] currentPath, Field newField) {
        if (currentPath == null || currentPath.length == 0) {
            return new Field[]{newField};
        }
        Field[] newPath = Arrays.copyOf(currentPath, currentPath.length + 1);
        newPath[currentPath.length] = newField;
        return newPath;
    }


    private static void createHeaderRow(Workbook workbook, Sheet sheet, List<FieldInfo> fields, List<String> alternativeHeaders) {
        Row headerRow = sheet.createRow(0);
        CellStyle headerStyle = createHeaderStyle(workbook);

        for (int i = 0; i < fields.size(); i++) {
            Cell cell = headerRow.createCell(i);
            String headerValue = (alternativeHeaders != null && i < alternativeHeaders.size() && alternativeHeaders.get(i) != null)
                    ? alternativeHeaders.get(i)
                    : fields.get(i).displayName;
            cell.setCellValue(headerValue);
            cell.setCellStyle(headerStyle);
        }
    }

    private static CellStyle createHeaderStyle(Workbook workbook) {
        CellStyle headerStyle = workbook.createCellStyle();
        headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        Font headerFont = workbook.createFont();
        headerFont.setBold(true);
        headerStyle.setFont(headerFont);

        headerStyle.setBorderBottom(BorderStyle.THIN);
        headerStyle.setBorderTop(BorderStyle.THIN);
        headerStyle.setBorderLeft(BorderStyle.THIN);
        headerStyle.setBorderRight(BorderStyle.THIN);

        return headerStyle;
    }

     private static <T> void createDataRows(Sheet sheet, List<T> entities, List<FieldInfo> fields) {
        DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        SimpleDateFormat utilDateFormatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

        int rowNum = 1;

        CellStyle defaultStyle = sheet.getWorkbook().createCellStyle();
        defaultStyle.setBorderBottom(BorderStyle.THIN);
        defaultStyle.setBorderTop(BorderStyle.THIN);
        defaultStyle.setBorderLeft(BorderStyle.THIN);
        defaultStyle.setBorderRight(BorderStyle.THIN);
        // Consider adding data format styles here if needed (e.g., for numbers, dates)
        // CreationHelper createHelper = sheet.getWorkbook().getCreationHelper();
        // CellStyle dateCellStyle = ...; dateCellStyle.setDataFormat(createHelper.createDataFormat().getFormat("yyyy-mm-dd"));
        // CellStyle numberCellStyle = ...;


        for (T entity : entities) {
            Row row = sheet.createRow(rowNum++);
            for (int i = 0; i < fields.size(); i++) {
                Cell cell = row.createCell(i);
                 cell.setCellStyle(defaultStyle); // Apply default border style
                setFieldValue(cell, entity, fields.get(i), dateFormatter, dateTimeFormatter, utilDateFormatter);
            }
        }
    }

    private static void setColumnWidths(Sheet sheet, int columnCount) {
        for (int i = 0; i < columnCount; i++) {
            sheet.autoSizeColumn(i);
            int currentWidth = sheet.getColumnWidth(i);
            int maxWidth = 256 * 50; // Max 50 chars
            int minWidth = 256 * 10; // Min 10 chars
            if (currentWidth > maxWidth) {
                sheet.setColumnWidth(i, maxWidth);
            } else if (currentWidth < minWidth) {
                 sheet.setColumnWidth(i, minWidth);
            } else {
                 sheet.setColumnWidth(i, currentWidth + 256); // Add padding
            }
        }
    }

    private static <T> void setFieldValue(Cell cell, T entity, FieldInfo fieldInfo,
                                          DateTimeFormatter dateFormatter,
                                          DateTimeFormatter dateTimeFormatter,
                                          SimpleDateFormat utilDateFormatter) {
        try {
            Object value = getFieldValue(entity, fieldInfo.fieldPath);

            if (value == null) {
                cell.setBlank();
                return;
            }

            if (value instanceof String) {
                cell.setCellValue((String) value);
            } else if (value instanceof LocalDate) {
                cell.setCellValue(((LocalDate) value).format(dateFormatter));
            } else if (value instanceof LocalDateTime) {
                cell.setCellValue(((LocalDateTime) value).format(dateTimeFormatter));
            } else if (value instanceof java.util.Date) {
                cell.setCellValue(utilDateFormatter.format((java.util.Date) value));
            } else if (value instanceof Calendar) {
                cell.setCellValue(utilDateFormatter.format(((Calendar) value).getTime()));
            } else if (value instanceof Boolean) {
                cell.setCellValue((Boolean) value);
            } else if (value instanceof Integer) {
                cell.setCellValue((Integer) value);
            } else if (value instanceof Long) {
                long longVal = (Long) value;
                if (longVal > -9007199254740991L && longVal < 9007199254740991L) {
                     cell.setCellValue(longVal);
                } else {
                     cell.setCellValue(String.valueOf(longVal));
                }
            } else if (value instanceof Double) {
                cell.setCellValue((Double) value);
            } else if (value instanceof Float) {
                cell.setCellValue(((Float) value).doubleValue());
            } else if (value instanceof Short) {
                cell.setCellValue(((Short) value).doubleValue());
            } else if (value instanceof Byte) {
                cell.setCellValue(((Byte) value).doubleValue());
            } else if (value instanceof Character) {
                 cell.setCellValue(String.valueOf(value));
            } else if (value instanceof BigDecimal) {
                 cell.setCellValue(((BigDecimal) value).doubleValue());
                 // Consider cell.setCellValue(((BigDecimal) value).toPlainString()); for text precision
            } else if (value instanceof BigInteger) {
                 try {
                     cell.setCellValue(((BigInteger) value).doubleValue());
                 } catch (NumberFormatException nfe) {
                      cell.setCellValue(((BigInteger) value).toString());
                 }
            } else if (value instanceof UUID) {
                cell.setCellValue(value.toString());
            } else if (value.getClass().isEnum()) {
                cell.setCellValue(((Enum<?>) value).name());
            } else {
                cell.setCellValue(value.toString());
            }
        } catch (Exception e) {
             // logger.error("Error setting cell value for field path '{}' in entity {}: {}", buildFieldPath(null, fieldInfo.field), entity, e.getMessage(), e); // More detailed logging
             cell.setCellValue("!Error"); // Indicate error in cell
        }
    }


    private static Object getFieldValue(Object entity, Field[] fieldPath) throws IllegalAccessException {
        Object currentObject = entity;
        for (Field field : fieldPath) {
            if (currentObject == null) {
                return null;
            }
            field.setAccessible(true);
            currentObject = field.get(currentObject);
        }
        return currentObject;
    }

    private static boolean isSimpleType(Class<?> type) {
        return SIMPLE_TYPES.contains(type) || type.isEnum();
    }

    private static boolean isJpaEntity(Class<?> clazz) {
        if (clazz.isAnnotationPresent(jakarta.persistence.Entity.class)) {
             return true;
        }
         try {
             Class<?> javaxEntityAnnotation = Class.forName("javax.persistence.Entity");
             // Use isAnnotationPresent with the Class object
             return clazz.isAnnotationPresent((Class<? extends java.lang.annotation.Annotation>) javaxEntityAnnotation);
         } catch (ClassNotFoundException | ClassCastException e) { // Catch potential ClassCastException too
             return false;
         }
    }


    private static String formatFieldName(String fieldName) {
        if (fieldName == null || fieldName.isEmpty()) {
            return "";
        }
        String[] words = fieldName.split("(?<=[a-z])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])|_"); // Also split by underscore
        return Arrays.stream(words)
                .filter(word -> !word.isEmpty())
                .map(word -> Character.toUpperCase(word.charAt(0)) + word.substring(1).toLowerCase()) // Capitalize first, lower rest
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