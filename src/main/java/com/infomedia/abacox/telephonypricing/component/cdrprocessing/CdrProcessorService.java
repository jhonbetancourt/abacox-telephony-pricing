// File: com/infomedia/abacox/telephonypricing/cdr/CdrProcessorService.java
package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import com.infomedia.abacox.telephonypricing.component.utils.CompressionZipUtil;
import com.infomedia.abacox.telephonypricing.component.utils.XXHash128Util;
import com.infomedia.abacox.telephonypricing.db.entity.*;
import com.infomedia.abacox.telephonypricing.multitenancy.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;

@Service
@Log4j2
public class CdrProcessorService {

    private final CdrEnrichmentService cdrEnrichmentService;
    private final CdrValidationService cdrValidationService;
    private final List<CdrProcessor> cdrProcessors;
    private final CommunicationLocationLookupService commLocationLookupService;
    private final EmployeeLookupService employeeLookupService;
    private final FileInfoPersistenceService fileInfoPersistenceService;
    private final CallRecordPersistenceService callRecordPersistenceService;
    private final FailedCallRecordPersistenceService failedCallRecordPersistenceService;
    private final PersistenceQueueService persistenceQueueService;
    private final FileProcessingTrackerService trackerService;

    @PersistenceContext
    private EntityManager entityManager;

    public CdrProcessorService(CdrEnrichmentService cdrEnrichmentService,
            CdrValidationService cdrValidationService,
            List<CdrProcessor> cdrProcessors,
            CommunicationLocationLookupService commLocationLookupService,
            EmployeeLookupService employeeLookupService,
            FileInfoPersistenceService fileInfoPersistenceService,
            CallRecordPersistenceService callRecordPersistenceService,
            FailedCallRecordPersistenceService failedCallRecordPersistenceService,
            PersistenceQueueService persistenceQueueService,
            FileProcessingTrackerService trackerService) {
        this.cdrEnrichmentService = cdrEnrichmentService;
        this.cdrValidationService = cdrValidationService;
        this.cdrProcessors = cdrProcessors;
        this.commLocationLookupService = commLocationLookupService;
        this.employeeLookupService = employeeLookupService;
        this.fileInfoPersistenceService = fileInfoPersistenceService;
        this.callRecordPersistenceService = callRecordPersistenceService;
        this.failedCallRecordPersistenceService = failedCallRecordPersistenceService;
        this.persistenceQueueService = persistenceQueueService;
        this.trackerService = trackerService;
    }

    @Transactional(propagation = Propagation.REQUIRED, readOnly = true)
    public void processCdrBatch(List<LineProcessingContext> batch) {
        // Pre-register count in tracker BEFORE processing/queueing
        Map<Long, Integer> countsByFile = new HashMap<>();
        for (LineProcessingContext ctx : batch) {
            if (ctx.getFileInfoId() != null) {
                countsByFile.merge(ctx.getFileInfoId(), 1, Integer::sum);
            }
        }
        countsByFile.forEach(trackerService::incrementPendingCount);

        // Capture TenantContext to propagate to parallel threads
        String tenantId = TenantContext.getTenant();

        // Use custom ForkJoinPool to prevent current thread (with transaction) from
        // participating
        try (ForkJoinPool customPool = new ForkJoinPool(
                Math.min(batch.size(), Runtime.getRuntime().availableProcessors()))) {
            customPool.submit(() -> {
                batch.parallelStream().forEach(ctx -> {
                    try {
                        TenantContext.setTenant(tenantId);
                        processSingleCdrLineInternal(ctx);
                    } finally {
                        TenantContext.clear();
                    }
                });
            }).get(); // Wait for completion
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Processing interrupted", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Processing failed", e.getCause());
        }
    }

    public ProcessingOutcome processSingleCdrLineSync(LineProcessingContext lineProcessingContext) {
        return processSingleCdrLineInternal(lineProcessingContext);
    }

    /**
     * Processes a single CDR line context and returns the result.
     * Does NOT persist or queue anything.
     */
    public ProcessedCdrResult processCdrData(LineProcessingContext lineProcessingContext) {
        String cdrLine = lineProcessingContext.getCdrLine();
        CommunicationLocation targetCommLocation = lineProcessingContext.getCommLocation();
        CdrData cdrData = null;

        try {
            FileInfo currentFileInfo = lineProcessingContext.getFileInfo();
            CdrProcessor processor = lineProcessingContext.getCdrProcessor();

            cdrData = processor.evaluateFormat(
                    cdrLine,
                    targetCommLocation,
                    lineProcessingContext.getCommLocationExtensionLimits(),
                    lineProcessingContext.getHeaderPositions());

            if (cdrData == null) {
                return ProcessedCdrResult.builder()
                        .outcome(ProcessingOutcome.SKIPPED)
                        .build();
            }

            cdrData.setFileInfo(currentFileInfo);
            cdrData.setCommLocationId(targetCommLocation.getId());

            boolean isValid = cdrValidationService.validateInitialCdrData(cdrData);
            if (!isValid || cdrData.isMarkedForQuarantine()) {
                return buildResult(cdrData, null, ProcessingOutcome.QUARANTINED);
            }

            cdrData = cdrEnrichmentService.enrichCdr(cdrData, lineProcessingContext);

            if (cdrData.isMarkedForQuarantine()) {
                return buildResult(cdrData, null, ProcessingOutcome.QUARANTINED);
            } else {
                return buildResult(cdrData, targetCommLocation, ProcessingOutcome.SUCCESS);
            }

        } catch (Exception e) {
            log.error("Unhandled exception processing CDR line: {}", cdrLine, e);

            if (cdrData == null) {
                cdrData = new CdrData();
                cdrData.setRawCdrLine(cdrLine);
                if (lineProcessingContext.getFileInfo() != null) {
                    cdrData.setFileInfo(lineProcessingContext.getFileInfo());
                }
                cdrData.setCommLocationId(targetCommLocation.getId());
            }

            cdrData.setMarkedForQuarantine(true);
            cdrData.setQuarantineReason(e.getMessage());
            cdrData.setQuarantineStep("UNHANDLED_EXCEPTION");

            return buildResult(cdrData, null, ProcessingOutcome.QUARANTINED);
        }
    }

    private ProcessedCdrResult buildResult(CdrData cdrData, CommunicationLocation commLocation,
            ProcessingOutcome outcome) {
        QuarantineErrorType errorType = null;
        String errorMessage = null;
        String errorStep = null;

        if (outcome == ProcessingOutcome.QUARANTINED) {
            errorType = resolveErrorType(cdrData.getQuarantineStep());
            errorMessage = cdrData.getQuarantineReason();
            errorStep = cdrData.getQuarantineStep();
        }

        return ProcessedCdrResult.builder()
                .tenantId(TenantContext.getTenant())
                .cdrData(cdrData)
                .commLocation(commLocation)
                .outcome(outcome)
                .errorType(errorType)
                .errorMessage(errorMessage)
                .errorStep(errorStep)
                .build();
    }

    private ProcessingOutcome processSingleCdrLineInternal(LineProcessingContext lineProcessingContext) {
        ProcessedCdrResult result = processCdrData(lineProcessingContext);

        if (result.getOutcome() == ProcessingOutcome.SKIPPED) {
            // If skipped, we must decrement the tracker immediately
            if (lineProcessingContext.getFileInfo() != null) {
                trackerService.decrementPendingCount(lineProcessingContext.getFileInfo().getId(), 1);
            }
            return ProcessingOutcome.SKIPPED;
        }

        // Submit to persistence queue
        persistenceQueueService.submit(result);

        return result.getOutcome();
    }

    private QuarantineErrorType resolveErrorType(String step) {
        if (step == null)
            return QuarantineErrorType.INITIAL_VALIDATION_ERROR;
        try {
            return QuarantineErrorType.valueOf(step);
        } catch (IllegalArgumentException e) {
            return QuarantineErrorType.INITIAL_VALIDATION_ERROR;
        }
    }

    private CdrProcessor getProcessorForPlantType(Long plantTypeId) {
        return cdrProcessors.stream()
                .filter(p -> p.getPlantTypeIdentifiers().contains(plantTypeId))
                .findFirst()
                .orElseThrow(
                        () -> new IllegalArgumentException("No CDR processor found for plant type ID: " + plantTypeId));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean reprocessCallRecord(Long callRecordId) {
        CallRecord callRecord = entityManager.find(CallRecord.class, callRecordId);
        if (callRecord == null) {
            log.info("Reprocessing request for CallRecord ID {} failed: Record not found.", callRecordId);
            return false;
        }

        // 1. Retrieve Raw String via File Scan
        String cdrString;
        try {
            cdrString = findRawCdrLineInFile(callRecord.getFileInfoId(), callRecord.getCtlHash());
        } catch (Exception e) {
            log.error("Failed to retrieve raw CDR line for CallRecord ID {}", callRecordId, e);
            return false;
        }

        if (cdrString == null) {
            log.error("Original CDR line not found in file for CallRecord ID {} (Hash: {})", callRecordId,
                    callRecord.getCtlHash());
            return false;
        }

        Optional<LineProcessingContext> contextOpt = buildReprocessingContext(
                cdrString,
                callRecord.getCommLocationId(),
                callRecord.getFileInfoId());

        if (contextOpt.isEmpty()) {
            return false;
        }

        CdrData processedCdrData = executeReprocessingLogic(contextOpt.get());

        if (processedCdrData.isMarkedForQuarantine()) {
            failedCallRecordPersistenceService.quarantineRecord(
                    processedCdrData,
                    resolveErrorType(processedCdrData.getQuarantineStep()),
                    processedCdrData.getQuarantineReason(),
                    "reprocessCallRecord_" + processedCdrData.getQuarantineStep(),
                    callRecordId);
            entityManager.remove(callRecord);
            return false;
        } else {
            // Update using Sync method
            callRecordPersistenceService.mapCdrDataToCallRecord(processedCdrData, callRecord,
                    contextOpt.get().getCommLocation());
            entityManager.merge(callRecord);
            return true;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean reprocessFailedCallRecord(Long failedCallRecordId) {
        FailedCallRecord failedCallRecord = entityManager.find(FailedCallRecord.class, failedCallRecordId);
        if (failedCallRecord == null) {
            return false;
        }

        // 1. Retrieve Raw String via File Scan
        String cdrString;
        try {
            cdrString = findRawCdrLineInFile(failedCallRecord.getFileInfoId(), failedCallRecord.getCtlHash());
        } catch (Exception e) {
            log.error("Failed to retrieve raw CDR line for FailedCallRecord ID {}", failedCallRecordId, e);
            return false;
        }

        if (cdrString == null) {
            log.error("Original CDR line not found in file for FailedCallRecord ID {} (Hash: {})", failedCallRecordId,
                    failedCallRecord.getCtlHash());
            return false;
        }

        Optional<LineProcessingContext> contextOpt = buildReprocessingContext(
                cdrString,
                failedCallRecord.getCommLocationId(),
                failedCallRecord.getFileInfoId());

        if (contextOpt.isEmpty()) {
            return false;
        }

        CdrData processedCdrData = executeReprocessingLogic(contextOpt.get());

        if (processedCdrData.isMarkedForQuarantine()) {
            QuarantineErrorType errorType = resolveErrorType(processedCdrData.getQuarantineStep());

            failedCallRecord.setErrorType(errorType.name());
            failedCallRecord.setErrorMessage(processedCdrData.getQuarantineReason());
            failedCallRecord.setProcessingStep("reprocessFailedCallRecord_" + processedCdrData.getQuarantineStep());
            entityManager.merge(failedCallRecord);
            return false;
        } else {
            CallRecord newCallRecord = callRecordPersistenceService.saveOrUpdateCallRecord(processedCdrData,
                    contextOpt.get().getCommLocation());
            if (newCallRecord != null) {
                entityManager.remove(failedCallRecord);
                return true;
            }
            return false;
        }
    }

    private Optional<LineProcessingContext> buildReprocessingContext(String cdrLine, Long commLocationId,
            Long fileInfoId) {
        if (commLocationId == null)
            return Optional.empty();

        Optional<CommunicationLocation> commLocationOpt = commLocationLookupService.findById(commLocationId);
        if (commLocationOpt.isEmpty())
            return Optional.empty();

        CommunicationLocation commLocation = commLocationOpt.get();
        FileInfo fileInfo = fileInfoPersistenceService.findById(fileInfoId);

        CdrProcessor processor = getProcessorForPlantType(commLocation.getPlantTypeId());
        Map<Long, ExtensionLimits> extensionLimits = employeeLookupService.getExtensionLimits();
        Map<Long, List<ExtensionRange>> extensionRanges = employeeLookupService.getExtensionRanges();

        Map<String, Integer> headerPositions = new HashMap<>();
        if (fileInfo != null) {
            Optional<FileInfoData> fileDataOpt = fileInfoPersistenceService.getOriginalFileData(fileInfoId);
            if (fileDataOpt.isPresent()) {
                try (InputStream is = fileDataOpt.get().content();
                        InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8);
                        BufferedReader br = new BufferedReader(reader)) {

                    String line;
                    int linesChecked = 0;
                    while ((line = br.readLine()) != null && linesChecked < 5) {
                        line = line.trim();
                        if (!line.isEmpty() && processor.isHeaderLine(line)) {
                            headerPositions = processor.parseHeader(line);
                            break;
                        }
                        linesChecked++;
                    }
                } catch (IOException e) {
                    log.error("Failed to read header from FileInfo ID {} for reprocessing.", fileInfoId, e);
                }
            }
        }

        LineProcessingContext context = LineProcessingContext.builder()
                .cdrLine(cdrLine)
                .commLocation(commLocation)
                .cdrProcessor(processor)
                .extensionRanges(extensionRanges)
                .extensionLimits(extensionLimits)
                .fileInfo(fileInfo)
                .headerPositions(headerPositions)
                .build();

        return Optional.of(context);
    }

    /**
     * Reads the associated file stream and scans for the line matching the specific
     * hash.
     * This replaces the need to store the string in the DB.
     */
    private String findRawCdrLineInFile(Long fileInfoId, UUID targetHash) throws IOException {
        if (fileInfoId == null || targetHash == null)
            return null;

        Optional<FileInfoData> fileDataOpt = fileInfoPersistenceService.getOriginalFileData(fileInfoId);
        if (fileDataOpt.isEmpty())
            return null;

        try (InputStream is = fileDataOpt.get().content();
                InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8);
                BufferedReader br = new BufferedReader(reader)) {

            String line;
            while ((line = br.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty())
                    continue;

                // Re-calculate hash exactly as CdrData does it
                UUID lineHash = XXHash128Util.hash(trimmed.getBytes(StandardCharsets.UTF_8));

                if (lineHash.equals(targetHash)) {
                    return trimmed;
                }
            }
        }
        return null;
    }

    private CdrData executeReprocessingLogic(LineProcessingContext context) {
        CdrData cdrData = context.getCdrProcessor().evaluateFormat(
                context.getCdrLine(),
                context.getCommLocation(),
                context.getCommLocationExtensionLimits(),
                context.getHeaderPositions());

        if (cdrData == null) {
            cdrData = new CdrData();
            cdrData.setRawCdrLine(context.getCdrLine());
            cdrData.setMarkedForQuarantine(true);
            cdrData.setQuarantineReason("Parser returned null during reprocessing.");
            cdrData.setQuarantineStep(QuarantineErrorType.PARSER_ERROR.name());
        }
        cdrData.setFileInfo(context.getFileInfo());
        cdrData.setCommLocationId(context.getCommLocationId());

        boolean isValid = cdrValidationService.validateInitialCdrData(cdrData);
        if (!isValid || cdrData.isMarkedForQuarantine()) {
            return cdrData;
        }

        return cdrEnrichmentService.enrichCdr(cdrData, context);
    }
}