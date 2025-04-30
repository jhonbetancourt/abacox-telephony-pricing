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

     // Inject the specific parser implementation for Cisco CM 6.0
     @Qualifier("ciscoCm60Parser")
     private final CdrParser cdrParser;

     private final CdrEnrichmentService enrichmentService;
     private final PersistenceService persistenceService;
     private final LookupService lookupService; // For fetching CommunicationLocation

     /**
      * Processes a stream of CDR data.
      *
      * @param inputStream The input stream containing CDR lines.
      * @param metadata    Metadata about the CDR source.
      */
     public void processCdrStream(InputStream inputStream, CdrProcessingRequest metadata) {
         log.info("Starting CDR processing for source: '{}', CommLocationID: {}",
                 metadata.getSourceDescription(), metadata.getCommunicationLocationId());

         // 1. Validate Communication Location
         Optional<CommunicationLocation> commLocationOpt = lookupService.findCommunicationLocationById(metadata.getCommunicationLocationId());
         if (commLocationOpt.isEmpty()) {
             log.error("Cannot process CDRs: CommunicationLocation with ID {} not found or inactive.", metadata.getCommunicationLocationId());
             // Consider throwing a specific exception or returning a status object
             return;
         }
         CommunicationLocation commLocation = commLocationOpt.get();

         // 2. Initialize Counters and Header Map
         AtomicLong processedLines = new AtomicLong(0);
         AtomicLong successfulRecords = new AtomicLong(0);
         AtomicLong failedRecords = new AtomicLong(0);
         Map<String, Integer> headerMap = null; // Store parsed header map

         // 3. Process Stream Line by Line
         try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
             String line;
             while ((line = reader.readLine()) != null) {
                 long currentLineNum = processedLines.incrementAndGet();
                 log.trace("Processing line {}: {}", currentLineNum, line);

                 // Skip blank lines
                 if (line.trim().isEmpty()) {
                     log.trace("Skipping blank line {}", currentLineNum);
                     continue;
                 }

                 // Handle Header Line
                 if (cdrParser.isHeaderLine(line)) {
                     try {
                         headerMap = cdrParser.parseHeader(line);
                         log.info("Detected and parsed header line.");
                     } catch (Exception e) {
                         log.error("Failed to parse header line: {}", line, e);
                         // If header parsing fails, we might not be able to process subsequent lines
                         // Quarantine the header line itself? Or stop processing?
                         createAndSaveFailure(line, metadata, commLocation, "Header Parsing", "Failed to parse header: " + e.getMessage(), null);
                         failedRecords.incrementAndGet();
                         // Decide whether to continue or stop based on requirements
                         // For now, let's stop if header parsing fails critically
                         log.error("Stopping processing due to header parse failure.");
                         break;
                     }
                     continue; // Skip processing the header line as a record
                 }

                 // Check if Header is Available (if required by parser)
                 if (headerMap == null) {
                      log.warn("Skipping line {} because header has not been detected/parsed yet: {}", currentLineNum, line);
                      // Quarantine lines encountered before a header
                      createAndSaveFailure(line, metadata, commLocation, "Parsing", "Header Missing or Not Yet Parsed", null);
                      failedRecords.incrementAndGet();
                      continue;
                 }

                 // Process Data Line
                 Optional<RawCdrDto> rawCdrOpt = Optional.empty();
                 String failureStep = "Parsing"; // Default step
                 String failureMsg = "Unknown parsing error";
                 String callingNumForFailure = null; // Store calling number if available for failure record

                 try {
                     // a. Parse Line
                     rawCdrOpt = cdrParser.parseLine(line, headerMap);

                     if (rawCdrOpt.isPresent()) {
                         RawCdrDto rawCdr = rawCdrOpt.get();
                         rawCdr.setFileInfoId(metadata.getFileInfoId()); // Add file info if available
                         callingNumForFailure = rawCdr.getCallingPartyNumber(); // Get for potential failure record

                         // b. Enrich Record
                         failureStep = "Enrichment";
                         failureMsg = "Enrichment failed"; // Default enrichment failure message
                         Optional<CallRecord> enrichedRecordOpt = enrichmentService.enrichCdr(rawCdr, commLocation);

                         if (enrichedRecordOpt.isPresent()) {
                             // c. Persist Record
                             failureStep = "Persistence";
                             failureMsg = "Failed to save CallRecord"; // Default persistence failure message
                             CallRecord savedRecord = persistenceService.saveCallRecord(enrichedRecordOpt.get());
                             // saveCallRecord throws exception on failure, so if we reach here, it succeeded.
                             successfulRecords.incrementAndGet();

                         } else {
                             // Enrichment service returned empty, indicating an issue during enrichment
                             log.warn("Enrichment failed for line {}", currentLineNum);
                             failedRecords.incrementAndGet();
                             createAndSaveFailure(line, metadata, commLocation, failureStep, failureMsg, callingNumForFailure);
                         }
                     } else {
                         // Parser returned empty, indicating an invalid line format or skipped line
                         log.warn("Parser skipped or failed line {}", currentLineNum);
                         failedRecords.incrementAndGet();
                         // failureMsg set by parser or use a generic one
                         createAndSaveFailure(line, metadata, commLocation, failureStep, "Invalid or Skipped CDR line format", null);
                     }
                 } catch (Exception e) {
                     // Catch exceptions during parsing, enrichment, or persistence
                     log.error("Exception processing line {}: {}", currentLineNum, line, e);
                     failedRecords.incrementAndGet();
                     // Use the step where the exception likely occurred
                     createAndSaveFailure(line, metadata, commLocation, failureStep, e.getMessage(), callingNumForFailure);
                 }
             } // End while loop
         } catch (IOException e) {
             log.error("IOException while reading CDR stream for source '{}': {}", metadata.getSourceDescription(), e.getMessage(), e);
             // Handle stream reading error - potentially mark the whole batch as failed?
             // Consider creating a single failure record for the stream error itself.
             createAndSaveFailure("IOException reading stream", metadata, commLocation, "Stream Reading", e.getMessage(), null);
             failedRecords.incrementAndGet(); // Count the stream error as one failure
         }

         // 4. Log Summary
         log.info("Finished CDR processing for source: '{}'. Total lines processed: {}, Successful records: {}, Failed records: {}",
                 metadata.getSourceDescription(), processedLines.get(), successfulRecords.get(), failedRecords.get());
     }

     /**
      * Helper method to create and save a FailedCallRecord.
      * Encapsulates the building and saving logic.
      */
     private void createAndSaveFailure(String line, CdrProcessingRequest metadata, CommunicationLocation commLocation,
                                       String step, String message, String extension) {
         try {
             FailedCallRecord failure = FailedCallRecord.builder()
                     .cdrString(line) // Store the original line
                     .commLocationId(commLocation.getId())
                     .employeeExtension(extension) // May be null if parsing failed early
                     .errorType(step.toUpperCase() + "_ERROR") // e.g., PARSING_ERROR, ENRICHMENT_ERROR
                     .errorMessage(message != null && message.length() > 1000 ? message.substring(0, 1000) : message) // Limit message size
                     .fileInfoId(metadata.getFileInfoId()) // Link to file if available
                     .processingStep(step) // Record where it failed
                     //.failedTimestamp(LocalDateTime.now()) // Record failure time
                    // .reprocessingStatus(0) // Default to pending
                     .build();
             persistenceService.saveFailedCallRecord(failure);
         } catch (Exception ex) {
             // Log critically if even saving the failure record fails
             log.error("CRITICAL: Failed to save failure record for line: [{}]. Error: {}", line.substring(0, Math.min(line.length(), 100))+"...", ex.getMessage(), ex);
         }
     }
 }