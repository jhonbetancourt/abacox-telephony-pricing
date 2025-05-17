package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.CallRecord;
import com.infomedia.abacox.telephonypricing.entity.CommunicationLocation;
import com.infomedia.abacox.telephonypricing.entity.FailedCallRecord;
import com.infomedia.abacox.telephonypricing.entity.FileInfo;
import com.infomedia.abacox.telephonypricing.repository.CallRecordRepository;
import com.infomedia.abacox.telephonypricing.repository.FailedCallRecordRepository;
import com.infomedia.abacox.telephonypricing.repository.FileInfoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Log4j2
public class CdrProcessingService {

    private final CoreLookupService coreLookupService;
    private final EnrichmentService enrichmentService;
    private final CallRecordRepository callRecordRepository;
    private final FailedCallRecordRepository failedCallRecordRepository;
    private final FileInfoRepository fileInfoRepository;
    private final Map<String, CdrProcessor> cdrProcessors; // Injected map of CdrProcessor beans

    @Transactional // Process entire stream in one transaction or handle per CDR
    public void processCdrStream(String originalFilename, InputStream inputStream, Long commLocationId) {
        log.info("Starting CDR processing for file: {}, commLocationId: {}", originalFilename, commLocationId);

        Optional<CommunicationLocation> commLocationOpt = coreLookupService.findCommunicationLocationById(commLocationId);
        if (commLocationOpt.isEmpty()) {
            log.error("CommunicationLocation not found or inactive for ID: {}", commLocationId);
            // Optionally create a FailedCallRecord for the whole file if needed
            return;
        }
        CommunicationLocation commLocation = commLocationOpt.get();

        FileInfo fileInfo = FileInfo.builder()
                .filename(originalFilename)
                .parentId(commLocationId.intValue()) // Assuming parentId is commLocationId
                .date(LocalDateTime.now())
                .directory(commLocation.getDirectory()) // Or a specific input directory
                .type(commLocation.getPlantType() != null ? commLocation.getPlantType().getName() : "UNKNOWN_CDR")
                // Size and checksum would ideally be set if the InputStream source provides them
                .build();
        fileInfo = fileInfoRepository.save(fileInfo);

        CdrProcessor processor = getCdrProcessor(commLocation);
        if (processor == null) {
            log.error("No CDR processor found for PlantType: {}", commLocation.getPlantType().getName());
            saveFailedRecordForFile(fileInfo, "No processor for PlantType: " + commLocation.getPlantType().getName(), FailedCallRecordErrorType.UNKNOWN_CDR_TYPE, commLocationId);
            return;
        }

        Map<String, Integer> columnMapping = null;
        int lineNumber = 0;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                String trimmedLine = line.trim();
                if (trimmedLine.isEmpty() || trimmedLine.startsWith(";")) { // Skip empty lines and comments
                    continue;
                }

                // Cisco CDR specific: Header row check
                if (columnMapping == null) {
                    if (trimmedLine.toLowerCase().contains(CiscoCm60CdrProcessor.HDR_CDR_RECORD_TYPE.toLowerCase())) {
                         try {
                            columnMapping = processor.establishColumnMapping(trimmedLine);
                            continue; // Header processed, move to next line
                        } catch (CdrProcessingException e) {
                            log.error("Failed to establish column mapping from header: {}. File: {}", trimmedLine, originalFilename, e);
                            saveFailedRecordForFile(fileInfo, "Invalid header: " + e.getMessage(), FailedCallRecordErrorType.PARSING_ERROR, commLocationId);
                            return; // Stop processing if header is bad
                        }
                    } else {
                        // If first non-comment line is not a header, and we expect one (like for Cisco)
                        log.warn("Expected header line but found data line or unknown format at line {}: {}", lineNumber, trimmedLine);
                        saveFailedCdr(trimmedLine, null, "Missing or unrecognized header", FailedCallRecordErrorType.PARSING_ERROR, commLocationId, fileInfo.getId(), "HeaderCheck");
                        continue; // Or stop processing, depending on strictness
                    }
                }
                
                if (columnMapping == null) { // Should not happen if header logic is correct
                    log.error("Column mapping not established after header. Skipping file.");
                    saveFailedRecordForFile(fileInfo, "Column mapping not established", FailedCallRecordErrorType.PARSING_ERROR, commLocationId);
                    return;
                }


                String cdrHash = CdrHelper.calculateSha256(trimmedLine);
                if (callRecordRepository.existsByCdrHash(cdrHash)) {
                    log.warn("Duplicate CDR detected (hash: {}), skipping line {}: {}", cdrHash, lineNumber, trimmedLine);
                    saveFailedCdr(trimmedLine, cdrHash, "Duplicate CDR hash", FailedCallRecordErrorType.CDR_ALREADY_PROCESSED, commLocationId, fileInfo.getId(), "DuplicateCheck");
                    continue;
                }

                RawCiscoCdrData rawData = null;
                try {
                    rawData = processor.parseCdrLine(trimmedLine, commLocation, columnMapping);
                    CallRecord callRecord = mapRawToCallRecord(rawData, commLocation, cdrHash, fileInfo);
                    enrichmentService.enrichCallRecord(callRecord, rawData, commLocation);
                    
                    // Final validation before save (e.g. ensure essential fields are populated)
                    if (callRecord.getTelephonyTypeId() == null || callRecord.getServiceDate() == null) {
                        throw new CdrProcessingException("Essential fields missing after enrichment for CDR: " + trimmedLine);
                    }

                    callRecordRepository.save(callRecord);
                    log.debug("Successfully processed and saved CDR line {}: {}", lineNumber, trimmedLine);

                } catch (CdrProcessingException e) {
                    log.error("Error processing CDR line {} (File: {}): {}. Line: {}", lineNumber, originalFilename, e.getMessage(), trimmedLine, e);
                    saveFailedCdr(trimmedLine, cdrHash, e.getMessage(), FailedCallRecordErrorType.PARSING_ERROR, commLocationId, fileInfo.getId(), "Parsing/Enrichment");
                } catch (Exception e) {
                    log.error("Unexpected error processing CDR line {} (File: {}): {}. Line: {}", lineNumber, originalFilename, e.getMessage(), trimmedLine, e);
                    saveFailedCdr(trimmedLine, cdrHash, "Unexpected: " + e.getMessage(), FailedCallRecordErrorType.UNKNOWN_CDR_TYPE, commLocationId, fileInfo.getId(), "Unexpected");
                }
            }
            log.info("Finished processing file: {}. Total lines processed: {}", originalFilename, lineNumber);
            // Update FileInfo status to success if needed
            fileInfo.setChecksum("PROCESSED_SUCCESS"); // Example status
            fileInfoRepository.save(fileInfo);

        } catch (IOException e) {
            log.error("Error reading CDR input stream for file: {}", originalFilename, e);
            saveFailedRecordForFile(fileInfo, "IOException: " + e.getMessage(), FailedCallRecordErrorType.PARSING_ERROR, commLocationId);
        }
    }

    private CdrProcessor getCdrProcessor(CommunicationLocation commLocation) {
        if (commLocation.getPlantType() == null || commLocation.getPlantType().getName() == null) {
            return null;
        }
        // Assuming PlantType.name corresponds to the bean name qualifier for CdrProcessor
        // e.g., "Cisco CM 6.0" -> "ciscoCm60CdrProcessor"
        // This needs a robust mapping. For now, a simple example:
        String plantTypeName = commLocation.getPlantType().getName();
        if ("CM_6_0".equalsIgnoreCase(plantTypeName) || "Cisco CallManager 6.0".equalsIgnoreCase(plantTypeName)) { // Match your PlantType name
            return cdrProcessors.get("ciscoCm60CdrProcessor");
        }
        // Add other types here
        return null;
    }

    private CallRecord mapRawToCallRecord(RawCiscoCdrData rawData, CommunicationLocation commLocation, String cdrHash, FileInfo fileInfo) {
        CallRecord cr = CallRecord.builder()
                .dial(rawData.getFinalCalledPartyNumber()) // This might be further refined by enrichment
                .commLocationId(commLocation.getId())
                .commLocation(commLocation)
                .serviceDate(rawData.getDateTimeOrigination())
                .employeeExtension(rawData.getCallingPartyNumber())
                .employeeAuthCode(rawData.getAuthCodeDescription())
                .destinationPhone(rawData.getFinalCalledPartyNumber()) // This is the "as dialed" or final effective number
                .duration(rawData.getDuration())
                .ringCount(rawData.getRingTime())
                .isIncoming(rawData.isIncomingCall())
                .trunk(rawData.isIncomingCall() ? rawData.getOrigDeviceName() : rawData.getDestDeviceName())
                .initialTrunk(rawData.isIncomingCall() ? rawData.getDestDeviceName() : rawData.getOrigDeviceName())
                .fileInfoId(fileInfo.getId())
                .fileInfo(fileInfo)
                .cdrHash(cdrHash)
                .transferCause(rawData.getImdexTransferCause().getValue())
                // employeeTransfer would be rawData.getLastRedirectDn() if it's an extension
                // This needs more specific logic based on whether lastRedirectDn is an employee
                .employeeTransfer( (rawData.getImdexTransferCause() != ImdexTransferCause.NO_TRANSFER) ? rawData.getLastRedirectDn() : null )
                .build();
        
        // Default non-null values for BigDecimal
        cr.setBilledAmount(BigDecimal.ZERO);
        cr.setPricePerMinute(BigDecimal.ZERO);
        cr.setInitialPrice(BigDecimal.ZERO);

        return cr;
    }
    
    private void saveFailedCdr(String cdrString, String cdrHash, String errorMessage, FailedCallRecordErrorType errorType, Long commLocationId, Long fileInfoId, String processingStep) {
        FailedCallRecord failedRecord = FailedCallRecord.builder()
                .commLocationId(commLocationId)
                .cdrString(cdrString)
                .errorType(errorType.getValue())
                .errorMessage(errorMessage)
                .fileInfoId(fileInfoId)
                .processingStep(processingStep)
                // originalCallRecordId is for reprocessing failures
                .build();
        failedCallRecordRepository.save(failedRecord);
    }

    private void saveFailedRecordForFile(FileInfo fileInfo, String errorMessage, FailedCallRecordErrorType errorType, Long commLocationId) {
        if (fileInfo != null) {
            fileInfo.setChecksum("PROCESSED_ERROR"); // Example status
            fileInfoRepository.save(fileInfo);
        }
         FailedCallRecord failedRecord = FailedCallRecord.builder()
                .commLocationId(commLocationId)
                .cdrString("File Level Error: " + (fileInfo != null ? fileInfo.getFilename() : "Unknown File"))
                .errorType(errorType.getValue())
                .errorMessage(errorMessage)
                .fileInfoId(fileInfo != null ? fileInfo.getId() : null)
                .processingStep("FileInitialization")
                .build();
        failedCallRecordRepository.save(failedRecord);
    }
}