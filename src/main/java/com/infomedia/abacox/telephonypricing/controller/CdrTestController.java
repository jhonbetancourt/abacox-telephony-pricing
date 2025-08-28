package com.infomedia.abacox.telephonypricing.controller;

import com.infomedia.abacox.telephonypricing.component.cdrprocessing.CdrProcessingExecutor;
import com.infomedia.abacox.telephonypricing.component.cdrprocessing.CiscoCm60CdrProcessor;
import com.infomedia.abacox.telephonypricing.component.cdrprocessing.FileInfoPersistenceService;
import com.infomedia.abacox.telephonypricing.dto.generic.MessageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@RequiredArgsConstructor
@RestController
@Tag(name = "CdrTestController", description = "CDR Test Controller")
@SecurityRequirements({
        @SecurityRequirement(name = "JWT_Token"),
        @SecurityRequirement(name = "Username")
})
@Log4j2
@RequestMapping("/api/cdr")
public class CdrTestController {

    private final CdrProcessingExecutor cdrProcessingExecutor;
    private final FileInfoPersistenceService fileInfoPersistenceService;

    @PostMapping(value = "/process", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Queue a CDR file or ZIP archive for processing",
            description = "Submits a CDR file (or a ZIP archive of CDR files). The file is saved and queued for reliable, asynchronous processing.")
    public MessageResponse processCdr(@Parameter(description = "The CDR file or ZIP archive to upload") @RequestParam("file") MultipartFile file) {
        log.info("Received file for queueing: {}", file.getOriginalFilename());

        String contentType = file.getContentType();
        String originalFilename = file.getOriginalFilename();

        int filesQueued = 0;
        if ("application/zip".equals(contentType) || (originalFilename != null && originalFilename.toLowerCase().endsWith(".zip"))) {
            filesQueued = queueZipFile(file);
        } else {
            filesQueued = queueSingleFile(file);
        }

        return new MessageResponse(String.format("%d file(s) queued for processing successfully.", filesQueued));
    }

    private int queueSingleFile(MultipartFile file) {
        log.info("Queueing single file: {}", file.getOriginalFilename());
        try {
            fileInfoPersistenceService.createOrGetFileInfo(
                    file.getOriginalFilename(),
                    CiscoCm60CdrProcessor.PLANT_TYPE_IDENTIFIER,
                    "ROUTED_STREAM",
                    file.getBytes()
            );
            return 1;
        } catch (IOException e) {
            log.error("Error reading bytes for single file: {}", file.getOriginalFilename(), e);
            throw new RuntimeException("Failed to queue single file: " + file.getOriginalFilename(), e);
        }
    }

    private int queueZipFile(MultipartFile zipFile) {
        log.info("Queueing files from ZIP archive: {}", zipFile.getOriginalFilename());
        int fileCount = 0;
        try (ZipInputStream zis = new ZipInputStream(zipFile.getInputStream())) {
            ZipEntry zipEntry;
            while ((zipEntry = zis.getNextEntry()) != null) {
                if (!zipEntry.isDirectory()) {
                    log.info("Queueing file from ZIP: {}", zipEntry.getName());
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buffer = new byte[4096];
                    int len;
                    while ((len = zis.read(buffer)) > 0) {
                        baos.write(buffer, 0, len);
                    }
                    fileInfoPersistenceService.createOrGetFileInfo(
                            zipEntry.getName(),
                            CiscoCm60CdrProcessor.PLANT_TYPE_IDENTIFIER,
                            "ROUTED_STREAM",
                            baos.toByteArray()
                    );
                    fileCount++;
                }
                zis.closeEntry();
            }
        } catch (IOException e) {
            log.error("Error processing ZIP file: {}", zipFile.getOriginalFilename(), e);
            throw new RuntimeException("Failed to queue ZIP file: " + zipFile.getOriginalFilename(), e);
        }
        return fileCount;
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
}