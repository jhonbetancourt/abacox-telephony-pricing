// File: com/infomedia/abacox/telephonypricing/cdr/CdrRoutingService.java
package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.CommunicationLocation;
import com.infomedia.abacox.telephonypricing.entity.FileInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional; // Keep this for the outer method

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

@Service
@Log4j2
@RequiredArgsConstructor
public class CdrRoutingService {

    private final CommunicationLocationLookupService commLocationLookupService;
    private final CdrFileProcessorService cdrFileProcessorService;
    private final FileInfoPersistenceService fileInfoPersistenceService;
    private final FailedCallRecordPersistenceService failedCallRecordPersistenceService;
    private final CdrConfigService cdrConfigService;
    private final List<CdrTypeProcessor> cdrTypeProcessors;

    private CdrTypeProcessor getProcessorForPlantType(Long plantTypeId) {
        return cdrTypeProcessors.stream()
                .filter(p -> p.getPlantTypeIdentifier().equals(plantTypeId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No CDR processor found for plant type ID: " + plantTypeId));
    }


    @Transactional // Manages transaction for the whole stream processing
    public void routeAndProcessCdrStream(String filename, InputStream inputStream, Long plantTypeId) {
        log.info("Starting CDR stream routing and processing for file: {}, PlantTypeID: {}", filename, plantTypeId);

        CdrTypeProcessor initialParser = getProcessorForPlantType(plantTypeId);
        log.debug("Using initial parser: {}", initialParser.getClass().getSimpleName());

        FileInfo fileInfo = fileInfoPersistenceService.createOrGetFileInfo(filename, null, "ROUTED_STREAM");
        if (fileInfo == null || fileInfo.getId() == null) {
            log.error("Failed to create or get FileInfo for stream: {}. Aborting processing.", filename);
            CdrData streamErrorData = new CdrData();
            streamErrorData.setRawCdrLine("STREAM_FILEINFO_ERROR_ROUTING: " + filename);
            // Cannot set commLocationId yet as it's not determined
            failedCallRecordPersistenceService.quarantineRecord(streamErrorData, QuarantineErrorType.IO_EXCEPTION,
                "Failed to establish FileInfo for routed stream " + filename, "RouteStreamInit_FileInfo", null);
            return;
        }
        Long fileInfoId = fileInfo.getId(); // Get the ID after it's persisted/fetched
        log.debug("Using FileInfo ID: {} for routed stream: {}", fileInfoId, filename);


        boolean headerProcessedByInitialParser = false;
        long lineCount = 0;
        long routedCdrCount = 0;
        long unroutableCdrCount = 0;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineCount++;
                String trimmedLine = line.trim();
                if (trimmedLine.isEmpty()) continue;

                log.trace("Routing line {}: {}", lineCount, trimmedLine);

                if (!headerProcessedByInitialParser && initialParser.isHeaderLine(trimmedLine)) {
                    initialParser.parseHeader(trimmedLine);
                    headerProcessedByInitialParser = true;
                    log.info("Processed header line using initial parser {} for stream: {}", initialParser.getClass().getSimpleName(), filename);
                    continue;
                }

                if (!headerProcessedByInitialParser) {
                    log.warn("Skipping line {} as header has not been processed by initial parser: {}", lineCount, trimmedLine);
                    CdrData tempData = new CdrData(); tempData.setRawCdrLine(trimmedLine); tempData.setFileInfo(fileInfo);
                    failedCallRecordPersistenceService.quarantineRecord(tempData,
                            QuarantineErrorType.MISSING_HEADER, "CDR data encountered before header (routing stage)", "RouteStream_HeaderCheck", null);
                    unroutableCdrCount++;
                    continue;
                }

                CdrData preliminaryCdrData = initialParser.evaluateFormat(trimmedLine, null);

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
                    log.debug("Line {} routed to CommLocation ID: {}", lineCount, targetCommLocation.getId());
                    CdrTypeProcessor finalProcessor = getProcessorForPlantType(targetCommLocation.getPlantTypeId());
                    // Assuming header is globally parsed by initialParser if plant types are same,
                    // or finalProcessor handles its own header state if different.
                    cdrFileProcessorService.processSingleCdrLine(trimmedLine, fileInfoId, targetCommLocation, finalProcessor); // Pass ID
                    routedCdrCount++;
                } else {
                    log.warn("Could not determine target CommunicationLocation for line {}: {}", lineCount, trimmedLine);
                    preliminaryCdrData.setCommLocationId(null); // Explicitly null as it's unroutable
                    failedCallRecordPersistenceService.quarantineRecord(preliminaryCdrData,
                            QuarantineErrorType.PENDING_ASSOCIATION, "Could not route CDR to a CommunicationLocation", "CdrRoutingService_Unroutable", null);
                    unroutableCdrCount++;
                }

                if ((routedCdrCount + unroutableCdrCount) > 0 && (routedCdrCount + unroutableCdrCount) % cdrConfigService.CDR_PROCESSING_BATCH_SIZE == 0) {
                    log.info("Routing service processed a batch of {} lines from stream {}. Routed: {}, Unroutable: {}",
                            cdrConfigService.CDR_PROCESSING_BATCH_SIZE, filename, routedCdrCount, unroutableCdrCount);
                }
            }
            log.info("Finished routing and processing stream: {}. Total lines read: {}, Routed CDRs: {}, Unroutable CDRs: {}",
                    filename, lineCount, routedCdrCount, unroutableCdrCount);

        } catch (IOException e) {
            log.error("Error reading CDR stream for routing: {}", filename, e);
            CdrData tempData = new CdrData(); tempData.setRawCdrLine("STREAM_ROUTING_READ_ERROR"); tempData.setFileInfo(fileInfo);
            failedCallRecordPersistenceService.quarantineRecord(tempData,
                    QuarantineErrorType.IO_EXCEPTION, e.getMessage(), "RouteStream_IOException", null);
        }
    }
}