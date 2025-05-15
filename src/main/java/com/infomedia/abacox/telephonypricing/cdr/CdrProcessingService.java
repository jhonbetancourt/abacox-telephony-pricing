package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.CallRecord;
import com.infomedia.abacox.telephonypricing.entity.CommunicationLocation;
import com.infomedia.abacox.telephonypricing.entity.FailedCallRecord;
import com.infomedia.abacox.telephonypricing.repository.CallRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

@Service
@RequiredArgsConstructor
@Log4j2
public class CdrProcessingService {

    @Qualifier("ciscoCm60Parser") // Inject the appropriate parser
    private final CdrParser cdrParser;

    private final CdrEnrichmentService enrichmentService;
    private final PersistenceService persistenceService;
    private final CallRecordRepository callRecordRepository;
    private final EntityLookupService entityLookupService;

    /**
     * Processes a stream of CDR data.
     *
     * @param inputStream The input stream containing CDR lines.
     * @param metadata    Metadata about the CDR source.
     */
    public void processCdrStream(InputStream inputStream, CdrProcessingRequest metadata) {
        log.info("Starting CDR processing for source: '{}', CommLocationID: {}",
                metadata.getSourceDescription(), metadata.getCommunicationLocationId());

        Optional<CommunicationLocation> commLocationOpt = entityLookupService.findCommunicationLocationById(metadata.getCommunicationLocationId());
        if (commLocationOpt.isEmpty()) {
            log.info("Cannot process CDRs: CommunicationLocation with ID {} not found or inactive.", metadata.getCommunicationLocationId());
            return;
        }
        CommunicationLocation commLocation = commLocationOpt.get();

        AtomicLong totalLinesRead = new AtomicLong(0);
        AtomicLong successfulRecords = new AtomicLong(0);
        AtomicLong failedRecords = new AtomicLong(0);
        AtomicLong skippedDuplicateRecords = new AtomicLong(0);
        Map<String, Integer> headerMap = null;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                long currentLineNum = totalLinesRead.incrementAndGet();
                log.info("Processing line {}: {}", currentLineNum, line);

                if (line.trim().isEmpty()) { continue; }

                if (cdrParser.isHeaderLine(line)) {
                    try {
                        headerMap = cdrParser.parseHeader(line);
                        log.info("Detected and parsed header line.");
                    } catch (Exception e) {
                        log.info("Failed to parse header line: {}", line, e);
                        createAndSaveFailure(line, metadata, commLocation, "Header Parsing", "Failed to parse header: " + e.getMessage(), null);
                        failedRecords.incrementAndGet();
                        log.info("Stopping processing due to header parse failure.");
                        break;
                    }
                    continue;
                }

                // Skip data type definition line (simple check)
                String trimmedUpperLine = line.trim().toUpperCase();
                if (trimmedUpperLine.startsWith("INTEGER,") || trimmedUpperLine.startsWith("VARCHAR(")) {
                    log.info("Skipping data type definition line {}: {}", currentLineNum, line.substring(0, Math.min(line.length(), 100)) + "...");
                    continue;
                }

                if (headerMap == null) {
                     log.info("Skipping line {} because header has not been detected/parsed yet: {}", currentLineNum, line);
                     createAndSaveFailure(line, metadata, commLocation, "Parsing", "Header Missing or Not Yet Parsed", null);
                     failedRecords.incrementAndGet();
                     continue;
                }

                // Process Data Line
                // Change variable type here
                Optional<StandardizedCallEventDto> standardDtoOpt = Optional.empty();
                String failureStep = "Parsing";
                String failureMsg = "Unknown parsing error";
                String callingNumForFailure = null; // Still useful for failure context

                try {
                    // a. Parse Line (returns Standardized DTO now)
                    standardDtoOpt = cdrParser.parseLine(line, headerMap, metadata);

                    if (standardDtoOpt.isPresent()) {
                        StandardizedCallEventDto standardDto = standardDtoOpt.get();
                        // Get hash from the DTO
                        String cdrHash = standardDto.getCdrHash();
                        callingNumForFailure = standardDto.getCallingPartyNumber();

                        // *** Duplicate Check using hash from DTO ***
                        if (callRecordRepository.existsByCdrHash(cdrHash)) {
                            log.info("Skipping duplicate CDR line {} (Hash: {})", currentLineNum, cdrHash);
                            skippedDuplicateRecords.incrementAndGet();
                            continue; // Skip duplicate
                        }
                        // *** END: Duplicate Check ***

                        // b. Enrich Record (Pass the Standardized DTO)
                        failureStep = "Enrichment";
                        failureMsg = "Enrichment failed";
                        // Pass the standardized DTO to the enrichment service
                        Optional<CallRecord> enrichedRecordOpt = enrichmentService.enrichCdr(standardDto, commLocation);

                        if (enrichedRecordOpt.isPresent()) {
                            // c. Persist Record
                            failureStep = "Persistence";
                            failureMsg = "Failed to save CallRecord";
                            CallRecord savedRecord = persistenceService.saveCallRecord(enrichedRecordOpt.get());
                            successfulRecords.incrementAndGet();

                        } else {
                            // Enrichment failed
                            log.info("Enrichment failed for line {}", currentLineNum);
                            failedRecords.incrementAndGet();
                            createAndSaveFailure(line, metadata, commLocation, failureStep, failureMsg, callingNumForFailure);
                        }
                    } else {
                        // Parser failed for a data line
                        log.info("Parser skipped or failed data line {}", currentLineNum);
                        failedRecords.incrementAndGet();
                        createAndSaveFailure(line, metadata, commLocation, failureStep, "Invalid or Skipped CDR data line format", null);
                    }
                } catch (Exception e) {
                    // Catch exceptions during parsing, enrichment, or persistence
                     Throwable rootCause = e;
                    while (rootCause.getCause() != null && rootCause.getCause() != rootCause) {
                        rootCause = rootCause.getCause();
                    }
                    if (rootCause instanceof java.sql.SQLIntegrityConstraintViolationException && rootCause.getMessage().toLowerCase().contains("uk_call_record_cdr_hash")) {
                         log.info("Attempted to insert duplicate CDR line {} (Hash constraint violation). Skipping.", currentLineNum);
                         skippedDuplicateRecords.incrementAndGet();
                    } else {
                        log.info("Exception processing data line {}: {}", currentLineNum, line, e);
                        failedRecords.incrementAndGet();
                        createAndSaveFailure(line, metadata, commLocation, failureStep, e.getMessage(), callingNumForFailure);
                    }
                }
            } // End while loop
        } catch (IOException e) {
            log.info("IOException while reading CDR stream for source '{}': {}", metadata.getSourceDescription(), e.getMessage(), e);
            createAndSaveFailure("IOException reading stream", metadata, commLocation, "Stream Reading", e.getMessage(), null);
            failedRecords.incrementAndGet();
        }

        // 4. Log Summary
        log.info("Finished CDR processing for source: '{}'. Total lines read: {}, Successful records: {}, Skipped duplicates: {}, Failed/Quarantined records: {}",
                metadata.getSourceDescription(), totalLinesRead.get(), successfulRecords.get(), skippedDuplicateRecords.get(), failedRecords.get());
    }

    /**
     * Helper method to create and save a FailedCallRecord.
     */
    private void createAndSaveFailure(String line, CdrProcessingRequest metadata, CommunicationLocation commLocation,
                                      String step, String message, String extension) {
        try {
            FailedCallRecord failure = FailedCallRecord.builder()
                    .cdrString(line)
                    .commLocationId(commLocation.getId())
                    .employeeExtension(extension) // May be null
                    .errorType(step.toUpperCase() + "_ERROR")
                    .errorMessage(message != null && message.length() > 1000 ? message.substring(0, 1000) : message)
                    .fileInfoId(metadata.getFileInfoId())
                    .processingStep(step)
                    .build();
            persistenceService.saveFailedCallRecord(failure);
        } catch (Exception ex) {
            log.info("CRITICAL: Failed to save failure record for line: [{}]. Error: {}", line.substring(0, Math.min(line.length(), 100))+"...", ex.getMessage(), ex);
        }
    }
}