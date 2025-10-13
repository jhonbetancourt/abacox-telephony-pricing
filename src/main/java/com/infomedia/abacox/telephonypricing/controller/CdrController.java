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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@RequiredArgsConstructor
@RestController
@Tag(name = "CdrController", description = "CDR Controller")
@SecurityRequirements({@SecurityRequirement(name = "JWT_Token"), @SecurityRequirement(name = "Username")})
@Log4j2
@RequestMapping("/api/cdr")
public class CdrController {

    private final CdrProcessingExecutor cdrProcessingExecutor;
    private final FileInfoPersistenceService fileInfoPersistenceService;
    private final CdrFormatDetectorService cdrFormatDetectorService;
    private final CdrRoutingService cdrRoutingService;
    private final ConfigService configService;


    @PostMapping(value = "/process", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Queue a CDR file or ZIP archive for processing",
            description = "Submits a CDR file (or a ZIP archive of CDR files). The file format is automatically detected. The file is saved and queued for reliable, asynchronous processing.")
    public MessageResponse processCdr(@Parameter(description = "The CDR file or ZIP archive to upload") @RequestParam("file") MultipartFile file
            , @RequestHeader (value = "X-API-KEY") String apiKey) {

        if(!apiKey.equals(configService.getValue(ConfigKey.CDR_UPLOAD_API_KEY).asString())) {
            throw new SecurityException("Invalid API Key");
        }

        log.info("Received file for queueing: {}", file.getOriginalFilename());

        String contentType = file.getContentType();
        String originalFilename = file.getOriginalFilename();

        int filesQueued;
        if ("application/zip".equals(contentType) || (originalFilename != null && originalFilename.toLowerCase().endsWith(".zip"))) {
            filesQueued = queueZipFile(file);
        } else {
            filesQueued = queueSingleFile(file);
        }

        return new MessageResponse(String.format("%d file(s) queued for processing successfully.", filesQueued));
    }

    @PostMapping(value = "/processSingle", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Process a single CDR file synchronously",
            description = "Submits a single CDR file for immediate, synchronous processing. The result summary is returned in the response. Fails if the file has been processed before.")
    public ResponseEntity<CdrProcessingResultDto> processCdrSync(@Parameter(description = "The single CDR file to process") @RequestParam("file") MultipartFile file) {
        log.info("Received file for synchronous processing: {}", file.getOriginalFilename());

        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Uploaded file cannot be empty.");
        }
        String originalFilename = file.getOriginalFilename();
        if (originalFilename != null && originalFilename.toLowerCase().endsWith(".zip")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ZIP archives are not supported for synchronous processing. Please upload a single CDR file.");
        }

        try {
            CdrProcessingResultDto result = cdrRoutingService.routeAndProcessCdrStreamSync(
                    file.getOriginalFilename(),
                    file.getInputStream()
            );
            return ResponseEntity.ok(result);
        } catch (IOException e) {
            log.error("IO error during synchronous processing of file: {}", file.getOriginalFilename(), e);
            throw new UncheckedIOException("Failed to read uploaded file.", e);
        }
    }

    private int queueSingleFile(MultipartFile file) {
        String filename = file.getOriginalFilename();
        log.info("Attempting to queue single file: {}", filename);
        try {
            byte[] fileBytes = file.getBytes();
            if (fileBytes.length == 0) {
                log.warn("Uploaded file '{}' is empty and will be ignored.", filename);
                return 0;
            }
            Long plantTypeId = cdrFormatDetectorService.detectPlantType(fileBytes)
                    .orElseThrow(() -> new ValidationException("Could not recognize CDR format."));

            fileInfoPersistenceService.createOrGetFileInfo(
                    filename,
                    plantTypeId,
                    "ROUTED_STREAM",
                    fileBytes
            );
            log.info("Successfully queued single file: {}", filename);
            return 1;
        } catch (ValidationException e) {
            // Business logic failure, not an upload failure. Log and return OK.
            log.warn("Skipping file '{}' due to a processing error: {}", filename, e.getMessage());
            return 0; // 0 files successfully queued
        } catch (IOException e) {
            // This is a fundamental upload I/O failure. Propagate it to return an error response.
            log.error("IO error while reading single file '{}'.", filename, e);
            throw new UncheckedIOException("Failed to read uploaded file: " + filename, e);
        } catch (Exception e) {
            // Catch any other unexpected errors during the queuing logic. Treat as a processing failure.
            log.error("An unexpected error occurred while attempting to queue file '{}'. The file will be skipped.", filename, e);
            return 0;
        }
    }

    private int queueZipFile(MultipartFile zipFile) {
        String zipFilename = zipFile.getOriginalFilename();
        log.info("Processing files from ZIP archive: {}", zipFilename);
        int successfullyQueuedCount = 0;
        try (ZipInputStream zis = new ZipInputStream(zipFile.getInputStream())) {
            ZipEntry zipEntry;
            while ((zipEntry = zis.getNextEntry()) != null) {
                if (zipEntry.isDirectory()) {
                    zis.closeEntry();
                    continue;
                }

                String entryName = zipEntry.getName();
                try {
                    log.debug("Processing entry '{}' from ZIP '{}'", entryName, zipFilename);
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buffer = new byte[4096];
                    int len;
                    while ((len = zis.read(buffer)) > 0) {
                        baos.write(buffer, 0, len);
                    }
                    byte[] fileBytes = baos.toByteArray();

                    if (fileBytes.length == 0) {
                        log.warn("Skipping empty file '{}' inside ZIP archive '{}'.", entryName, zipFilename);
                        continue;
                    }

                    Long plantTypeId = cdrFormatDetectorService.detectPlantType(fileBytes)
                            .orElseThrow(() -> new ValidationException("Could not recognize CDR format."));

                    fileInfoPersistenceService.createOrGetFileInfo(
                            entryName,
                            plantTypeId,
                            "ROUTED_STREAM",
                            fileBytes
                    );

                    log.info("Successfully queued file from ZIP: {}", entryName);
                    successfullyQueuedCount++;

                } catch (ValidationException e) {
                    log.warn("Skipping file '{}' in ZIP '{}' due to a processing error: {}", entryName, zipFilename, e.getMessage());
                } catch (IOException e) {
                    log.error("IO error while reading entry '{}' from ZIP '{}'. Skipping this entry.", entryName, zipFilename, e);
                } catch (Exception e) {
                    log.error("An unexpected error occurred while processing entry '{}' in ZIP '{}'. Skipping this entry.", entryName, zipFilename, e);
                } finally {
                    zis.closeEntry();
                }
            }
        } catch (IOException e) {
            // This is a fundamental upload I/O failure (e.g., reading the zip stream itself). Propagate it.
            log.error("IO error while processing ZIP file '{}'. This is considered an upload failure.", zipFilename, e);
            throw new UncheckedIOException("Failed to read uploaded ZIP file: " + zipFilename, e);
        }
        log.info("Finished processing ZIP archive '{}'. Successfully queued {} file(s).", zipFilename, successfullyQueuedCount);
        return successfullyQueuedCount;
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
            cdrProcessingExecutor.submitFileReprocessing(fileInfoId);
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
    public ResponseEntity<byte[]> downloadCdrFile(@PathVariable Long fileInfoId) {
        log.info("Request to download original file for FileInfo ID: {}", fileInfoId);

        return fileInfoPersistenceService.getOriginalFileData(fileInfoId)
                .map(fileData -> {
                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
                    headers.setContentDispositionFormData("attachment", fileData.filename());
                    headers.setContentLength(fileData.content().length);

                    log.info("Serving file '{}' ({} bytes) for download.", fileData.filename(), fileData.content().length);
                    return new ResponseEntity<>(fileData.content(), headers, HttpStatus.OK);
                })
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "File not found or content is unavailable for FileInfo ID: " + fileInfoId));
    }
}