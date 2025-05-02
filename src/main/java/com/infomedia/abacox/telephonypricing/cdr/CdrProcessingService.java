// FILE: cdr/CdrProcessingService.java
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

    @Qualifier("ciscoCm60Parser")
    private final CdrParser cdrParser;

    private final CdrEnrichmentService enrichmentService; // Remains the main entry point for enrichment
    private final PersistenceService persistenceService;
    private final EntityLookupService entityLookupService; // Use for CommLocation lookup
    private final CallRecordRepository callRecordRepository;

    public void processCdrStream(InputStream inputStream, CdrProcessingRequest metadata) {
        log.info("Starting CDR processing for source: '{}', CommLocationID: {}",
                metadata.getSourceDescription(), metadata.getCommunicationLocationId());

        // Use EntityLookupService to find the CommunicationLocation
        Optional<CommunicationLocation> commLocationOpt = entityLookupService.findCommunicationLocationById(metadata.getCommunicationLocationId());
        if (commLocationOpt.isEmpty()) {
            log.error("Cannot process CDRs: CommunicationLocation with ID {} not found or inactive.", metadata.getCommunicationLocationId());
            // Maybe create a failure record even without commLocation?
            // createAndSaveFailure("Stream Start", metadata, null, "Setup", "CommunicationLocation not found: " + metadata.getCommunicationLocationId(), null);
            return;
        }
        CommunicationLocation commLocation = commLocationOpt.get();

        // ... rest of the processCdrStream method remains largely the same ...
        // It calls enrichmentService.enrichCdr(...) which now uses the new structure internally.

        AtomicLong totalLinesRead = new AtomicLong(0);
        AtomicLong successfulRecords = new AtomicLong(0);
        AtomicLong failedRecords = new AtomicLong(0);
        AtomicLong skippedDuplicateRecords = new AtomicLong(0);
        Map<String, Integer> headerMap = null;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                long currentLineNum = totalLinesRead.incrementAndGet();
                log.trace("Processing line {}: {}", currentLineNum, line);

                if (line.trim().isEmpty()) { continue; }

                if (cdrParser.isHeaderLine(line)) {
                    try {
                        headerMap = cdrParser.parseHeader(line);
                        log.info("Detected and parsed header line.");
                    } catch (Exception e) {
                        log.error("Failed to parse header line: {}", line, e);
                        createAndSaveFailure(line, metadata, commLocation, "Header Parsing", "Failed to parse header: " + e.getMessage(), null);
                        failedRecords.incrementAndGet();
                        log.error("Stopping processing due to header parse failure.");
                        break; // Stop if header fails
                    }
                    continue;
                }

                String trimmedUpperLine = line.trim().toUpperCase();
                if (trimmedUpperLine.startsWith("INTEGER,") || trimmedUpperLine.startsWith("VARCHAR(")) {
                    log.debug("Skipping data type definition line {}: {}", currentLineNum, line.substring(0, Math.min(line.length(), 100)) + "...");
                    continue;
                }

                if (headerMap == null) {
                     log.warn("Skipping line {} because header has not been detected/parsed yet: {}", currentLineNum, line);
                     createAndSaveFailure(line, metadata, commLocation, "Parsing", "Header Missing or Not Yet Parsed", null);
                     failedRecords.incrementAndGet();
                     continue;
                }

                Optional<StandardizedCallEventDto> standardDtoOpt = Optional.empty();
                String failureStep = "Parsing";
                String failureMsg = "Unknown parsing error";
                String callingNumForFailure = null;

                try {
                    standardDtoOpt = cdrParser.parseLine(line, headerMap, metadata);

                    if (standardDtoOpt.isPresent()) {
                        StandardizedCallEventDto standardDto = standardDtoOpt.get();
                        String cdrHash = standardDto.getCdrHash();
                        callingNumForFailure = standardDto.getCallingPartyNumber();

                        if (callRecordRepository.existsByCdrHash(cdrHash)) {
                            log.warn("Skipping duplicate CDR line {} (Hash: {})", currentLineNum, cdrHash);
                            skippedDuplicateRecords.incrementAndGet();
                            continue;
                        }

                        failureStep = "Enrichment";
                        failureMsg = "Enrichment failed";
                        Optional<CallRecord> enrichedRecordOpt = enrichmentService.enrichCdr(standardDto, commLocation);

                        if (enrichedRecordOpt.isPresent()) {
                            failureStep = "Persistence";
                            failureMsg = "Failed to save CallRecord";
                            CallRecord savedRecord = persistenceService.saveCallRecord(enrichedRecordOpt.get());
                            successfulRecords.incrementAndGet();
                        } else {
                            log.warn("Enrichment failed for line {}", currentLineNum);
                            failedRecords.incrementAndGet();
                            createAndSaveFailure(line, metadata, commLocation, failureStep, failureMsg, callingNumForFailure);
                        }
                    } else {
                        log.warn("Parser skipped or failed data line {}", currentLineNum);
                        failedRecords.incrementAndGet();
                        createAndSaveFailure(line, metadata, commLocation, failureStep, "Invalid or Skipped CDR data line format", null);
                    }
                } catch (Exception e) {
                     Throwable rootCause = e;
                    while (rootCause.getCause() != null && rootCause.getCause() != rootCause) {
                        rootCause = rootCause.getCause();
                    }
                    if (rootCause instanceof java.sql.SQLIntegrityConstraintViolationException && rootCause.getMessage().toLowerCase().contains("uk_call_record_cdr_hash")) {
                         log.warn("Attempted to insert duplicate CDR line {} (Hash constraint violation). Skipping.", currentLineNum);
                         skippedDuplicateRecords.incrementAndGet();
                    } else {
                        log.error("Exception processing data line {}: {}", currentLineNum, line, e);
                        failedRecords.incrementAndGet();
                        createAndSaveFailure(line, metadata, commLocation, failureStep, e.getMessage(), callingNumForFailure);
                    }
                }
            }
        } catch (IOException e) {
            log.error("IOException while reading CDR stream for source '{}': {}", metadata.getSourceDescription(), e.getMessage(), e);
            createAndSaveFailure("IOException reading stream", metadata, commLocation, "Stream Reading", e.getMessage(), null);
            failedRecords.incrementAndGet();
        }

        log.info("Finished CDR processing for source: '{}'. Total lines read: {}, Successful records: {}, Skipped duplicates: {}, Failed/Quarantined records: {}",
                metadata.getSourceDescription(), totalLinesRead.get(), successfulRecords.get(), skippedDuplicateRecords.get(), failedRecords.get());
    }

    private void createAndSaveFailure(String line, CdrProcessingRequest metadata, CommunicationLocation commLocation,
                                      String step, String message, String extension) {
        // This method remains the same, using PersistenceService
         try {
            FailedCallRecord failure = FailedCallRecord.builder()
                    .cdrString(line)
                    .commLocationId(commLocation != null ? commLocation.getId() : null) // Handle null commLocation
                    .employeeExtension(extension)
                    .errorType(step.toUpperCase() + "_ERROR")
                    .errorMessage(message != null && message.length() > 1000 ? message.substring(0, 1000) : message)
                    .fileInfoId(metadata.getFileInfoId())
                    .processingStep(step)
                    .build();
            persistenceService.saveFailedCallRecord(failure);
        } catch (Exception ex) {
            log.error("CRITICAL: Failed to save failure record for line: [{}]. Error: {}", line.substring(0, Math.min(line.length(), 100))+"...", ex.getMessage(), ex);
        }
    }
}