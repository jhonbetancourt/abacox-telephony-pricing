package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.CallRecord;
import com.infomedia.abacox.telephonypricing.entity.CommunicationLocation;
import com.infomedia.abacox.telephonypricing.entity.FailedCallRecord;
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

    // Inject the specific parser implementation
    @Qualifier("ciscoCm60Parser")
    private final CdrParser cdrParser;

    private final CdrEnrichmentService enrichmentService;
    private final PersistenceService persistenceService;
    private final LookupService lookupService; // For fetching CommunicationLocation

    public void processCdrStream(InputStream inputStream, CdrProcessingRequest metadata) {
        log.info("Starting CDR processing for source: {}, CommLocationID: {}",
                metadata.getSourceDescription(), metadata.getCommunicationLocationId());

        Optional<CommunicationLocation> commLocationOpt = lookupService.findCommunicationLocationById(metadata.getCommunicationLocationId());
        if (commLocationOpt.isEmpty()) {
            log.error("Cannot process CDRs: CommunicationLocation with ID {} not found.", metadata.getCommunicationLocationId());
            // Potentially throw an exception or handle appropriately
            return;
        }
        CommunicationLocation commLocation = commLocationOpt.get();

        AtomicLong processedLines = new AtomicLong(0);
        AtomicLong successfulRecords = new AtomicLong(0);
        AtomicLong failedRecords = new AtomicLong(0);
        Map<String, Integer> headerMap = null; // Store parsed header

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                long currentLineNum = processedLines.incrementAndGet();
                log.trace("Processing line {}: {}", currentLineNum, line);

                if (cdrParser.isHeaderLine(line)) {
                    headerMap = cdrParser.parseHeader(line);
                    log.info("Detected header line.");
                    continue; // Skip header line from record processing
                }

                if (headerMap == null) {
                     log.warn("Skipping line {} because header has not been parsed yet: {}", currentLineNum, line);
                     // Quarantine? Or assume fixed format if header is optional/missing?
                     // For Cisco, header is usually present. Let's quarantine.
                     createAndSaveFailure(line, metadata, commLocation, "Parsing", "Header Missing", null);
                     failedRecords.incrementAndGet();
                     continue;
                }

                Optional<RawCdrDto> rawCdrOpt = Optional.empty();
                String failureStep = "Parsing";
                String failureMsg = "Unknown parsing error";
                CallRecord savedRecord = null;

                try {
                    rawCdrOpt = cdrParser.parseLine(line, headerMap);

                    if (rawCdrOpt.isPresent()) {
                        RawCdrDto rawCdr = rawCdrOpt.get();
                        rawCdr.setFileInfoId(metadata.getFileInfoId()); // Add file info if available

                        failureStep = "Enrichment";
                        failureMsg = "Enrichment failed"; // Default enrichment failure message
                        Optional<CallRecord> enrichedRecordOpt = enrichmentService.enrichCdr(rawCdr, commLocation);

                        if (enrichedRecordOpt.isPresent()) {
                            failureStep = "Persistence";
                            failureMsg = "Failed to save CallRecord";
                            savedRecord = persistenceService.saveCallRecord(enrichedRecordOpt.get());
                            if (savedRecord != null) {
                                successfulRecords.incrementAndGet();
                            } else {
                                // Persistence service failed without exception? Logged there.
                                failedRecords.incrementAndGet();
                                createAndSaveFailure(line, metadata, commLocation, failureStep, failureMsg, null);
                            }
                        } else {
                            // Enrichment service returned empty, indicating failure
                            failedRecords.incrementAndGet();
                            createAndSaveFailure(line, metadata, commLocation, failureStep, failureMsg, rawCdr.getCallingPartyNumber());
                        }
                    } else {
                        // Parser returned empty, indicating an invalid line (already logged by parser)
                        failedRecords.incrementAndGet();
                        createAndSaveFailure(line, metadata, commLocation, failureStep, "Invalid CDR line format", null);
                    }
                } catch (Exception e) {
                    // Catch exceptions during parsing, enrichment, or persistence
                    log.error("Exception processing line {}: {}", currentLineNum, line, e);
                    failedRecords.incrementAndGet();
                    createAndSaveFailure(line, metadata, commLocation, failureStep, e.getMessage(),
                                         rawCdrOpt.map(RawCdrDto::getCallingPartyNumber).orElse(null));
                }
            }
        } catch (IOException e) {
            log.error("IOException while reading CDR stream for source {}: {}", metadata.getSourceDescription(), e.getMessage(), e);
            // Handle stream reading error - potentially mark the whole batch as failed?
        }

        log.info("Finished CDR processing for source: {}. Total lines: {}, Successful: {}, Failed: {}",
                metadata.getSourceDescription(), processedLines.get(), successfulRecords.get(), failedRecords.get());
    }

    private void createAndSaveFailure(String line, CdrProcessingRequest metadata, CommunicationLocation commLocation,
                                      String step, String message, String extension) {
        try {
            FailedCallRecord failure = FailedCallRecord.builder()
                    .cdrString(line)
                    .commLocationId(commLocation.getId())
                    .employeeExtension(extension) // May be null if parsing failed early
                    .errorType(step.toUpperCase() + "_ERROR") // e.g., PARSING_ERROR
                    .errorMessage(message != null && message.length() > 1000 ? message.substring(0, 1000) : message) // Limit message size
                    .fileInfoId(metadata.getFileInfoId())
                    .processingStep(step)
                    .build();
            persistenceService.saveFailedCallRecord(failure);
        } catch (Exception ex) {
            log.error("CRITICAL: Failed to save failure record for line: {}", line, ex);
        }
    }
}