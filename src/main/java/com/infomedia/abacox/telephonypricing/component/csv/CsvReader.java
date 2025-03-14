package com.infomedia.abacox.telephonypricing.component.csv;

import com.opencsv.CSVParser;
import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.exceptions.CsvValidationException;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import lombok.extern.log4j.Log4j2;

/**
 * Utility class for efficient CSV file processing using OpenCSV.
 * Reads CSV data row by row without loading the entire file into memory.
 * Empty strings are read as null values.
 */
@Log4j2
public class CsvReader implements Closeable {
    private CSVReader csvReader;
    private char delimiter;
    private boolean hasHeader;
    private List<String> headers;
    private String sourceIdentifier;
    private long rowsProcessed;
    
    /**
     * Creates a CSV reader from a file path
     * 
     * @param filePath  the path to the CSV file
     * @param delimiter the delimiter character used in the CSV
     * @param hasHeader whether the CSV has a header row
     * @throws IOException if the file cannot be read
     */
    public CsvReader(String filePath, String delimiter, boolean hasHeader) throws IOException {
        this(new FileInputStream(filePath), delimiter, hasHeader);
        this.sourceIdentifier = filePath;
    }
    
    /**
     * Creates a CSV reader from an InputStream
     * 
     * @param inputStream the input stream containing CSV data
     * @param delimiter   the delimiter character used in the CSV
     * @param hasHeader   whether the CSV has a header row
     * @throws IOException if the stream cannot be read
     */
    public CsvReader(InputStream inputStream, String delimiter, boolean hasHeader) throws IOException {
        this.delimiter = delimiter.charAt(0);
        this.hasHeader = hasHeader;
        this.headers = new ArrayList<>();
        this.sourceIdentifier = "InputStream";
        this.rowsProcessed = 0;
        
        log.info("Initializing CSV reader for {} with delimiter '{}', hasHeader: {}", 
                 sourceIdentifier, delimiter, hasHeader);
        
        // Configure OpenCSV parser
        CSVParser parser = new CSVParserBuilder()
                .withSeparator(this.delimiter)
                .withIgnoreQuotations(false)
                .withQuoteChar('"')
                .withEscapeChar('\\')
                .build();
        
        // Create the CSV reader
        this.csvReader = new CSVReaderBuilder(new InputStreamReader(inputStream, StandardCharsets.UTF_8))
                .withCSVParser(parser)
                .withSkipLines(0)
                .build();
        
        // Read headers if needed
        if (hasHeader) {
            try {
                String[] headerFields = csvReader.readNext();
                if (headerFields != null) {
                    // Convert empty header values to non-empty placeholders
                    for (int i = 0; i < headerFields.length; i++) {
                        if (headerFields[i] == null || headerFields[i].trim().isEmpty()) {
                            headerFields[i] = "Column_" + (i + 1);
                            log.warn("Empty header at position {} replaced with '{}'", i, headerFields[i]);
                        }
                    }
                    headers.addAll(Arrays.asList(headerFields));
                    log.debug("CSV headers parsed: {}", headers);
                } else {
                    log.warn("CSV file is empty or header line could not be read");
                }
            } catch (CsvValidationException e) {
                log.error("Error parsing CSV header: {}", e.getMessage(), e);
                throw new IOException("Failed to parse CSV header", e);
            }
        }
    }
    
    /**
     * Process each row in the CSV file using the provided callback
     * Empty string values are converted to null.
     * 
     * @param rowProcessor callback function to process each row
     * @throws IOException if an error occurs while reading the file
     */
    public void processRows(Consumer<String[]> rowProcessor) throws IOException {
        log.info("Starting to process rows from {}", sourceIdentifier);
        rowsProcessed = 0;
        String[] fields;
        
        try {
            while ((fields = csvReader.readNext()) != null) {
                // Convert empty strings to null
                for (int i = 0; i < fields.length; i++) {
                    if (fields[i] != null && fields[i].trim().isEmpty()) {
                        fields[i] = null;
                    }
                }
                
                rowProcessor.accept(fields);
                rowsProcessed++;
                
                if (rowsProcessed % 10000 == 0) {
                    log.info("Processed {} rows from {}", rowsProcessed, sourceIdentifier);
                }
            }
            
            log.info("CSV processing complete. Total rows processed: {}", rowsProcessed);
        } catch (CsvValidationException e) {
            log.error("Error validating CSV row #{} from {}: {}", rowsProcessed + 1, 
                      sourceIdentifier, e.getMessage(), e);
            throw new IOException("CSV validation error", e);
        } catch (Exception e) {
            log.error("Error processing CSV row #{} from {}: {}", rowsProcessed + 1, 
                      sourceIdentifier, e.getMessage(), e);
            throw e;
        }
    }
    
    /**
     * Process each row in the CSV file as a map, with column headers as keys
     * Empty string values are converted to null.
     * 
     * @param rowProcessor callback function to process each row as a map
     * @throws IOException if an error occurs while reading the file
     * @throws IllegalStateException if the CSV does not have headers
     */
    public void processRowsAsMap(Consumer<CsvRow> rowProcessor) throws IOException {
        if (!hasHeader || headers.isEmpty()) {
            log.error("Cannot process CSV as map: No headers available");
            throw new IllegalStateException("CSV must have headers to process rows as maps");
        }
        
        log.info("Starting to process rows as maps from {}", sourceIdentifier);
        rowsProcessed = 0;
        String[] fields;
        
        try {
            while ((fields = csvReader.readNext()) != null) {
                // Convert empty strings to null
                for (int i = 0; i < fields.length; i++) {
                    if (fields[i] != null && fields[i].trim().isEmpty()) {
                        fields[i] = null;
                    }
                }
                
                CsvRow row = new CsvRow(headers, fields);
                rowProcessor.accept(row);
                rowsProcessed++;
                
                if (rowsProcessed % 10000 == 0) {
                    log.info("Processed {} rows from {}", rowsProcessed, sourceIdentifier);
                }
            }
            
            log.info("CSV processing complete. Total rows processed: {}", rowsProcessed);
        } catch (CsvValidationException e) {
            log.error("Error validating CSV row #{} from {}: {}", rowsProcessed + 1, 
                      sourceIdentifier, e.getMessage(), e);
            throw new IOException("CSV validation error", e);
        } catch (Exception e) {
            log.error("Error processing CSV row #{} from {}: {}", rowsProcessed + 1, 
                      sourceIdentifier, e.getMessage(), e);
            throw e;
        }
    }
    
    /**
     * Get the headers of the CSV file
     * 
     * @return the list of headers
     */
    public List<String> getHeaders() {
        return new ArrayList<>(headers);
    }
    
    /**
     * Get the total number of rows processed
     * 
     * @return the number of rows processed
     */
    public long getRowsProcessed() {
        return rowsProcessed;
    }
    
    @Override
    public void close() throws IOException {
        log.debug("Closing CSV reader for {}, processed {} rows", sourceIdentifier, rowsProcessed);
        if (csvReader != null) {
            csvReader.close();
        }
    }
    
    /**
     * Represents a row in the CSV with column name mapping
     */
    public static class CsvRow {
        private final List<String> headers;
        private final String[] values;
        
        public CsvRow(List<String> headers, String[] values) {
            this.headers = headers;
            this.values = values;
        }
        
        /**
         * Get a field value by its column index
         * 
         * @param index the column index
         * @return the field value, null if empty or not found
         */
        public String get(int index) {
            if (index >= 0 && index < values.length) {
                return values[index];
            }
            return null;
        }
        
        /**
         * Get a field value by its column name
         * 
         * @param columnName the column name
         * @return the field value, null if empty or not found
         */
        public String get(String columnName) {
            int index = headers.indexOf(columnName);
            if (index >= 0 && index < values.length) {
                return values[index];
            }
            return null;
        }
        
        /**
         * Get all field values in this row
         * 
         * @return array of field values
         */
        public String[] getValues() {
            return values.clone();
        }
    }
}