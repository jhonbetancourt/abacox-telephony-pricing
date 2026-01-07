package com.infomedia.abacox.telephonypricing.controller;

import com.infomedia.abacox.telephonypricing.component.cdrprocessing.*;
import com.infomedia.abacox.telephonypricing.component.configmanager.ConfigKey;
import com.infomedia.abacox.telephonypricing.component.configmanager.ConfigService;
import com.infomedia.abacox.telephonypricing.dto.generic.MessageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.ValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.*;
import java.util.List;

@RequiredArgsConstructor
@RestController
@Tag(name = "CdrController", description = "CDR Controller")
@SecurityRequirements(value = {
        @SecurityRequirement(name = "JWT_Token"),
        @SecurityRequirement(name = "Username"),
        @SecurityRequirement(name = "Tenant_Id")
})
@Log4j2
@RequestMapping("/api/cdr")
public class CdrController {

    private final CdrProcessingExecutor cdrProcessingExecutor;
    private final FileInfoPersistenceService fileInfoPersistenceService;
    private final CdrRoutingService cdrRoutingService;
    private final ConfigService configService;
    private final List<CdrProcessor> cdrProcessors;


    @PostMapping(value = "/process", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Queue a CDR file for processing",
            description = "Submits a CDR file for a specific plant type. The file is saved and queued for reliable, asynchronous processing.")
    public MessageResponse processCdr(
            @Parameter(description = "The CDR file to upload") @RequestParam("file") MultipartFile file,
            @Parameter(description = "The unique identifier for the plant type (e.g., PBX model)") @RequestParam("plantTypeId") Long plantTypeId,
            @RequestHeader (value = "X-API-KEY") String apiKey) {

        if(!apiKey.equals(configService.getValue(ConfigKey.CDR_UPLOAD_API_KEY).asString())) {
            throw new SecurityException("Invalid API Key");
        }

        log.info("Received file for queueing: {}, PlantTypeID: {}", file.getOriginalFilename(), plantTypeId);

        int filesQueued = queueSingleFile(file, plantTypeId);

        return new MessageResponse(String.format("%d file(s) queued for processing successfully.", filesQueued));
    }

    @PostMapping(value = "/processSingle", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Process a single CDR file synchronously",
            description = "Submits a single CDR file for immediate, synchronous processing for a specific plant type. The result summary is returned in the response. Fails if the file has been processed before.")
    public ResponseEntity<CdrProcessingResultDto> processCdrSync(
            @Parameter(description = "The single CDR file to process") @RequestParam("file") MultipartFile file,
            @Parameter(description = "The unique identifier for the plant type (e.g., PBX model)") @RequestParam("plantTypeId") Long plantTypeId) {

        log.info("Received file for synchronous processing: {}, PlantTypeID: {}",
                file.getOriginalFilename(), plantTypeId);

        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Uploaded file cannot be empty.");
        }

        File tempFile = null;
        try {
            // Create temporary file
            tempFile = File.createTempFile("sync_upload_", "_" + file.getOriginalFilename());
            tempFile.deleteOnExit();

            // Transfer multipart file to temp file (streaming)
            file.transferTo(tempFile);

            // Probe file using streaming approach
            CdrProcessor processor = getProcessorForPlantType(plantTypeId);
            List<String> initialLines = CdrUtil.readInitialLinesFromFile(tempFile);

            if (!processor.probe(initialLines)) {
                throw new ValidationException("File format is not valid for the specified plantTypeId.");
            }

            // Create or get FileInfo
            FileInfoPersistenceService.FileInfoCreationResult fileInfoResult =
                    fileInfoPersistenceService.createOrGetFileInfo(
                            file.getOriginalFilename(),
                            plantTypeId,
                            "SYNC_STREAM",
                            tempFile
                    );

            if (!fileInfoResult.isNew()) {
                throw new ValidationException(
                        "File with the same content has already been processed. FileInfo ID: " +
                                fileInfoResult.getFileInfo().getId());
            }

            // Process the file synchronously
            CdrProcessingResultDto result = cdrRoutingService.routeAndProcessCdrStreamSync(
                    fileInfoResult.getFileInfo()
            );

            return ResponseEntity.ok(result);

        } catch (ValidationException e) {
            log.warn("Validation error during sync processing: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IOException e) {
            log.error("IO error during synchronous processing of file: {}", file.getOriginalFilename(), e);
            throw new UncheckedIOException("Failed to process uploaded file.", e);
        } finally {
            // Ensure temp file is deleted
            if (tempFile != null && tempFile.exists()) {
                if (tempFile.delete()) {
                    log.debug("Temporary file deleted: {}", tempFile.getAbsolutePath());
                } else {
                    log.warn("Failed to delete temporary file: {}", tempFile.getAbsolutePath());
                }
            }
        }
    }

    private int queueSingleFile(MultipartFile file, Long plantTypeId) {
        String filename = file.getOriginalFilename();
        log.info("Attempting to queue single file: {}, PlantTypeID: {}", filename, plantTypeId);

        File tempFile = null;
        try {
            // Check if file is empty before creating temp file
            if (file.isEmpty()) {
                log.warn("Uploaded file '{}' is empty and will be ignored.", filename);
                return 0;
            }

            // Create temporary file
            tempFile = File.createTempFile("upload_", "_" + filename);
            tempFile.deleteOnExit(); // Backup cleanup in case of JVM crash

            // Transfer multipart file to temp file (streaming, no full load into memory)
            file.transferTo(tempFile);

            // Probe file using streaming approach
            CdrProcessor processor = getProcessorForPlantType(plantTypeId);
            List<String> initialLines = CdrUtil.readInitialLinesFromFile(tempFile);

            if (!processor.probe(initialLines)) {
                log.warn("File '{}' was rejected by the processor's probe for plantTypeId {}. Skipping queue.",
                        filename, plantTypeId);
                return 0;
            }

            // Create or get FileInfo using the temp file
            fileInfoPersistenceService.createOrGetFileInfo(
                    filename,
                    plantTypeId,
                    "ROUTED_STREAM",
                    tempFile
            );

            log.info("Successfully queued single file: {}", filename);
            return 1;

        } catch (ValidationException e) {
            log.warn("Skipping file '{}' due to a validation error: {}", filename, e.getMessage());
            return 0;
        } catch (IOException e) {
            log.error("IO error while processing single file '{}'.", filename, e);
            throw new UncheckedIOException("Failed to process uploaded file: " + filename, e);
        } catch (Exception e) {
            log.error("An unexpected error occurred while attempting to queue file '{}'. The file will be skipped.",
                    filename, e);
            return 0;
        } finally {
            // Ensure temp file is deleted
            if (tempFile != null && tempFile.exists()) {
                if (tempFile.delete()) {
                    log.debug("Temporary file deleted: {}", tempFile.getAbsolutePath());
                } else {
                    log.warn("Failed to delete temporary file: {}", tempFile.getAbsolutePath());
                }
            }
        }
    }

    private CdrProcessor getProcessorForPlantType(Long plantTypeId) {
        return cdrProcessors.stream()
                .filter(p -> p.getPlantTypeIdentifiers().contains(plantTypeId))
                .findFirst()
                .orElseThrow(() -> new ValidationException("No CDR processor found for plant type ID: " + plantTypeId));
    }

    @PostMapping("/reprocess/files")
    @Operation(summary = "Reprocess one or more previously processed files",
            description = "Submits a task to reprocess files based on their FileInfo IDs.")
    public MessageResponse reprocessFiles(@RequestBody List<Long> fileInfoIds) {
        if (fileInfoIds == null || fileInfoIds.isEmpty()) {
            return new MessageResponse("No FileInfo IDs provided for reprocessing.");
        }
        log.info("Received request to reprocess FileInfo IDs: {}", fileInfoIds);

        for (Long fileInfoId : fileInfoIds) {
            cdrProcessingExecutor.submitFileReprocessing(fileInfoId, true);
        }
        return new MessageResponse(String.format("Reprocessing task submitted for %d file(s).", fileInfoIds.size()));
    }

    @PostMapping("/reprocess/callRecords")
    @Operation(summary = "Reprocess one or more existing call records",
            description = "Submits a task to reprocess individual call records based on their IDs.")
    public MessageResponse reprocessCallRecords(@RequestBody List<Long> callRecordIds) {
        if (callRecordIds == null || callRecordIds.isEmpty()) {
            return new MessageResponse("No CallRecord IDs provided for reprocessing.");
        }
        log.info("Received request to reprocess CallRecord IDs: {}", callRecordIds);
        for (Long callRecordId : callRecordIds) {
            cdrProcessingExecutor.submitCallRecordReprocessing(callRecordId);
        }
        return new MessageResponse(String.format("Reprocessing task submitted for %d call record(s).", callRecordIds.size()));
    }

    @PostMapping("/reprocess/failedCallRecords")
    @Operation(summary = "Reprocess one or more failed/quarantined call records",
            description = "Submits a task to reprocess individual failed call records based on their IDs.")
    public MessageResponse reprocessFailedCallRecords(@RequestBody List<Long> failedCallRecordIds) {
        if (failedCallRecordIds == null || failedCallRecordIds.isEmpty()) {
            return new MessageResponse("No FailedCallRecord IDs provided for reprocessing.");
        }
        log.info("Received request to reprocess FailedCallRecord IDs: {}", failedCallRecordIds);
        for (Long failedCallRecordId : failedCallRecordIds) {
            cdrProcessingExecutor.submitFailedCallRecordReprocessing(failedCallRecordId);
        }
        return new MessageResponse(String.format("Reprocessing task submitted for %d failed call record(s).", failedCallRecordIds.size()));
    }

    @GetMapping("/download/{fileInfoId}")
    @Operation(summary = "Download the original CDR file",
            description = "Retrieves the original, unprocessed CDR file content based on its FileInfo ID.")
    public ResponseEntity<StreamingResponseBody> downloadCdrFile(@PathVariable Long fileInfoId) {
        log.info("Request to download original file for FileInfo ID: {}", fileInfoId);

        // 1. Fetch Metadata first (short transaction)
        FileInfoPersistenceService.FileInfoMetadata metadata = fileInfoPersistenceService.getFileMetadata(fileInfoId);

        if (metadata == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found for ID: " + fileInfoId);
        }

        // 2. Setup Headers
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.setContentDispositionFormData("attachment", metadata.filename());
        headers.setContentLength(metadata.size());

        // 3. Create Streaming Response
        // The lambda passed here will be executed by a Spring TaskExecutor.
        // Inside this lambda, we call the SERVICE method which starts its own Transaction.
        StreamingResponseBody responseBody = outputStream -> {
            try {
                fileInfoPersistenceService.streamFileContent(fileInfoId, outputStream);
                log.info("Successfully streamed file '{}' ({} bytes)", metadata.filename(), metadata.size());
            } catch (Exception e) {
                // Log and rethrow. Note: Client might see a broken stream if headers were already sent.
                log.error("Failed to stream file content for ID: {}", fileInfoId, e);
                throw new IOException("Error streaming file", e);
            }
        };

        return new ResponseEntity<>(responseBody, headers, HttpStatus.OK);
    }

}