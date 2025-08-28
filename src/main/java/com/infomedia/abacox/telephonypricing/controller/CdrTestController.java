package com.infomedia.abacox.telephonypricing.controller;

import com.infomedia.abacox.telephonypricing.component.cdrprocessing.CdrProcessingExecutor;
import com.infomedia.abacox.telephonypricing.component.cdrprocessing.CiscoCm60CdrProcessor;
import com.infomedia.abacox.telephonypricing.dto.generic.MessageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@RequiredArgsConstructor
@RestController
@Tag(name = "CdrTestController", description = "CDR Test Controller")
@Log4j2
@RequestMapping("/api/cdr")
public class CdrTestController {

    private final CdrProcessingExecutor cdrProcessingExecutor;

    @PostMapping(value = "/process", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Process a CDR file or ZIP archive",
            description = "Submits a CDR file (or a ZIP archive of CDR files) for asynchronous processing. The system will automatically detect if the file is a ZIP archive.")
    public MessageResponse processCdr(@Parameter(description = "The CDR file or ZIP archive to upload") @RequestParam("file") MultipartFile file) {
        log.info("Received file for processing: {}", file.getOriginalFilename());

        String contentType = file.getContentType();
        String originalFilename = file.getOriginalFilename();

        if ("application/zip".equals(contentType) || (originalFilename != null && originalFilename.toLowerCase().endsWith(".zip"))) {
            processZipFile(file);
        } else {
            processSingleFile(file);
        }

        return new MessageResponse("CDR processing started successfully.");
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

    private void processSingleFile(MultipartFile file) {
        log.info("Processing as a single file: {}", file.getOriginalFilename());
        try {
            cdrProcessingExecutor.submitCdrStreamProcessing(
                    file.getOriginalFilename(),
                    file.getInputStream(),
                    CiscoCm60CdrProcessor.PLANT_TYPE_IDENTIFIER
            );
        } catch (IOException e) {
            log.error("Error getting input stream for single file: {}", file.getOriginalFilename(), e);
            throw new RuntimeException("Failed to process single file: " + file.getOriginalFilename(), e);
        }
    }

    private void processZipFile(MultipartFile zipFile) {
        log.info("Processing as a ZIP archive: {}", zipFile.getOriginalFilename());
        try (ZipInputStream zis = new ZipInputStream(zipFile.getInputStream())) {
            ZipEntry zipEntry;
            while ((zipEntry = zis.getNextEntry()) != null) {
                if (!zipEntry.isDirectory()) {
                    log.info("Submitting file from ZIP archive: {}", zipEntry.getName());
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buffer = new byte[4096];
                    int len;
                    while ((len = zis.read(buffer)) > 0) {
                        baos.write(buffer, 0, len);
                    }
                    InputStream entryInputStream = new ByteArrayInputStream(baos.toByteArray());
                    cdrProcessingExecutor.submitCdrStreamProcessing(
                            zipEntry.getName(),
                            entryInputStream,
                            CiscoCm60CdrProcessor.PLANT_TYPE_IDENTIFIER
                    );
                }
                zis.closeEntry();
            }
        } catch (IOException e) {
            log.error("Error processing ZIP file: {}", zipFile.getOriginalFilename(), e);
            throw new RuntimeException("Failed to process ZIP file: " + zipFile.getOriginalFilename(), e);
        }
    }
}