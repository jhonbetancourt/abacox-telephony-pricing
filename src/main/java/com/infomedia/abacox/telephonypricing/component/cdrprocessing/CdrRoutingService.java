package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import com.infomedia.abacox.telephonypricing.db.entity.CommunicationLocation;
import com.infomedia.abacox.telephonypricing.db.entity.ExtensionRange;
import com.infomedia.abacox.telephonypricing.db.entity.FileInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@Log4j2
@RequiredArgsConstructor
public class CdrRoutingService {

    private final CommunicationLocationLookupService commLocationLookupService;
    private final CdrProcessorService cdrProcessorService;
    private final FileInfoPersistenceService fileInfoPersistenceService;
    private final FailedCallRecordPersistenceService failedCallRecordPersistenceService;
    private final List<CdrProcessor> cdrProcessors;
    private final EmployeeLookupService employeeLookupService;

    private CdrProcessor getProcessorForPlantType(Long plantTypeId) {
        return cdrProcessors.stream()
                .filter(p -> p.getPlantTypeIdentifier().equals(plantTypeId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No CDR processor found for plant type ID: " + plantTypeId));
    }

    /**
     * Orchestrates the processing of a CDR stream. This method is NOT transactional.
     * It reads the entire stream into memory to archive it, then processes it in batches.
     * Each batch is delegated to a separate, transactional service method.
     *
     * @param filename    Name of the CDR file/stream.
     * @param inputStream The CDR data stream.
     * @param plantTypeId The ID of the plant type for initial parsing.
     */
    protected void routeAndProcessCdrStreamInternal(String filename, InputStream inputStream, Long plantTypeId) {
        log.debug("Starting CDR stream routing and processing for file: {}, PlantTypeID: {}", filename, plantTypeId);

        CdrProcessor initialParser = getProcessorForPlantType(plantTypeId);
        log.debug("Using initial parser: {}", initialParser.getClass().getSimpleName());

        byte[] streamBytes;
        try {
            // Buffer the entire stream to get its content for archiving and reprocessing.
            // This is a trade-off for the feature of storing the file content.
            streamBytes = inputStream.readAllBytes();
            log.debug("Read {} bytes from input stream for file: {}", streamBytes.length, filename);
        } catch (IOException e) {
            log.debug("Failed to read input stream for file: {}. Aborting processing.", filename, e);
            CdrData streamErrorData = new CdrData();
            streamErrorData.setRawCdrLine("STREAM_READ_ERROR_ROUTING: " + filename);
            failedCallRecordPersistenceService.quarantineRecord(streamErrorData, QuarantineErrorType.IO_EXCEPTION,
                "Failed to read input stream " + filename, "RouteStreamInit_Read", null);
            return;
        }

        // Create the FileInfo record, now including the compressed file content for archival.
        FileInfo fileInfo = fileInfoPersistenceService.createOrGetFileInfo(filename, plantTypeId, "ROUTED_STREAM", streamBytes);
        Map<Long, ExtensionLimits> extensionLimits = employeeLookupService.getExtensionLimits();
        Map<Long, List<ExtensionRange>> extensionRanges = employeeLookupService.getExtensionRanges();

        if (fileInfo == null || fileInfo.getId() == null) {
            log.debug("Failed to create or get FileInfo for stream: {}. Aborting processing.", filename);
            CdrData streamErrorData = new CdrData();
            streamErrorData.setRawCdrLine("STREAM_FILEINFO_ERROR_ROUTING: " + filename);
            failedCallRecordPersistenceService.quarantineRecord(streamErrorData, QuarantineErrorType.IO_EXCEPTION,
                "Failed to establish FileInfo for routed stream " + filename, "RouteStreamInit_FileInfo", null);
            return;
        }
        log.debug("Using FileInfo ID: {} for routed stream: {}", fileInfo.getId(), filename);

        boolean headerProcessedByInitialParser = false;
        long lineCount = 0;
        long totalProcessedCount = 0;
        long unroutableCdrCount = 0;

        List<LineProcessingContext> batch = new ArrayList<>(CdrConfigService.CDR_PROCESSING_BATCH_SIZE);

        // Use the buffered byte array to create a new stream for processing.
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(streamBytes), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineCount++;
                String trimmedLine = line.trim();
                if (trimmedLine.isEmpty()) continue;

                log.trace("Routing line {}: {}", lineCount, trimmedLine);

                if (!headerProcessedByInitialParser && initialParser.isHeaderLine(trimmedLine)) {
                    initialParser.parseHeader(trimmedLine);
                    headerProcessedByInitialParser = true;
                    log.debug("Processed header line using initial parser {} for stream: {}", initialParser.getClass().getSimpleName(), filename);
                    continue;
                }

                if (!headerProcessedByInitialParser) {
                    log.debug("Skipping line {} as header has not been processed by initial parser: {}", lineCount, trimmedLine);
                    CdrData tempData = new CdrData(); tempData.setRawCdrLine(trimmedLine); tempData.setFileInfo(fileInfo);
                    failedCallRecordPersistenceService.quarantineRecord(tempData,
                            QuarantineErrorType.MISSING_HEADER, "CDR data encountered before header (routing stage)", "RouteStream_HeaderCheck", null);
                    unroutableCdrCount++;
                    continue;
                }

                CdrData preliminaryCdrData = initialParser.evaluateFormat(trimmedLine, null, null);

                if (preliminaryCdrData == null) {
                    log.trace("Initial parser returned null for line, skipping routing: {}", trimmedLine);
                    continue;
                }
                preliminaryCdrData.setRawCdrLine(trimmedLine);
                preliminaryCdrData.setFileInfo(fileInfo);

                Optional<CommunicationLocation> targetCommLocationOpt =
                        commLocationLookupService.findBestCommunicationLocation(
                                plantTypeId,
                                preliminaryCdrData.getCallingPartyNumber(),
                                preliminaryCdrData.getCallingPartyNumberPartition(),
                                preliminaryCdrData.getFinalCalledPartyNumber(),
                                preliminaryCdrData.getFinalCalledPartyNumberPartition(),
                                preliminaryCdrData.getLastRedirectDn(),
                                preliminaryCdrData.getLastRedirectDnPartition(),
                                preliminaryCdrData.getDateTimeOrigination()
                        );

                if (targetCommLocationOpt.isPresent()) {
                    CommunicationLocation targetCommLocation = targetCommLocationOpt.get();
                    CdrProcessor finalProcessor = getProcessorForPlantType(targetCommLocation.getPlantTypeId());
                    LineProcessingContext lineProcessingContext = LineProcessingContext.builder()
                            .cdrLine(trimmedLine)
                            .commLocation(targetCommLocation)
                            .cdrProcessor(finalProcessor)
                            .extensionRanges(extensionRanges)
                            .extensionLimits(extensionLimits)
                            .fileInfo(fileInfo)
                            .build();
                    batch.add(lineProcessingContext);
                } else {
                    log.debug("Could not determine target CommunicationLocation for line {}: {}", lineCount, trimmedLine);
                    preliminaryCdrData.setCommLocationId(null);
                    failedCallRecordPersistenceService.quarantineRecord(preliminaryCdrData,
                            QuarantineErrorType.PENDING_ASSOCIATION, "Could not route CDR to a CommunicationLocation", "CdrRoutingService_Unroutable", null);
                    unroutableCdrCount++;
                }

                if (batch.size() >= CdrConfigService.CDR_PROCESSING_BATCH_SIZE) {
                    log.debug("Processing a batch of {} CDRs...", batch.size());
                    cdrProcessorService.processCdrBatch(batch);
                    totalProcessedCount += batch.size();
                    batch.clear();
                }
            }

            if (!batch.isEmpty()) {
                log.debug("Processing the final batch of {} CDRs...", batch.size());
                cdrProcessorService.processCdrBatch(batch);
                totalProcessedCount += batch.size();
                batch.clear();
            }

            log.debug("Finished routing and processing stream: {}. Total lines read: {}, Processed CDRs: {}, Unroutable CDRs: {}",
                    filename, lineCount, totalProcessedCount, unroutableCdrCount);

        } catch (IOException e) {
            // This exception is now highly unlikely since we are reading from an in-memory byte array.
            log.debug("Critical error reading from in-memory CDR stream for routing: {}", filename, e);
            CdrData tempData = new CdrData(); tempData.setRawCdrLine("STREAM_ROUTING_IN_MEMORY_READ_ERROR"); tempData.setFileInfo(fileInfo);
            failedCallRecordPersistenceService.quarantineRecord(tempData,
                    QuarantineErrorType.IO_EXCEPTION, e.getMessage(), "RouteStream_InMemory_IOException", null);
        }
    }
}