package com.infomedia.abacox.telephonypricing.controller;

import com.infomedia.abacox.telephonypricing.component.cdrprocessing.CdrProcessingExecutor;
import com.infomedia.abacox.telephonypricing.component.cdrprocessing.CiscoCm60CdrProcessor;
import com.infomedia.abacox.telephonypricing.dto.generic.MessageResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@RequiredArgsConstructor
@RestController
@Tag(name = "CdrTestController", description = "CDR Test Controller")
/*@SecurityRequirements({
        @SecurityRequirement(name = "JWT_Token"),
        @SecurityRequirement(name = "Username")
})*/
@Log4j2
@RequestMapping("/api/cdr")
public class CdrTestController {

    private final CdrProcessingExecutor cdrProcessingExecutor;

    @PostMapping(value = "/process", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public MessageResponse processCdr(@RequestParam("file") MultipartFile file) {
        log.info("Received file for processing: {}", file.getOriginalFilename());

        String contentType = file.getContentType();
        String originalFilename = file.getOriginalFilename();

        // Check if the file is a ZIP archive by content type or file extension
        if ("application/zip".equals(contentType) || (originalFilename != null && originalFilename.toLowerCase().endsWith(".zip"))) {
            processZipFile(file);
        } else {
            processSingleFile(file);
        }

        return new MessageResponse("CDR processing started successfully.");
    }

    /**
     * Processes a single, non-archived CDR file.
     *
     * @param file The MultipartFile to process.
     */
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

    /**
     * Extracts and processes each file within a ZIP archive.
     *
     * @param zipFile The MultipartFile representing the ZIP archive.
     */
    private void processZipFile(MultipartFile zipFile) {
        log.info("Processing as a ZIP archive: {}", zipFile.getOriginalFilename());
        try (ZipInputStream zis = new ZipInputStream(zipFile.getInputStream())) {
            ZipEntry zipEntry;
            while ((zipEntry = zis.getNextEntry()) != null) {
                // Skip directories
                if (!zipEntry.isDirectory()) {
                    log.info("Submitting file from ZIP archive: {}", zipEntry.getName());

                    // We must read the entry into a byte array because the downstream
                    // processor will close the stream. If we passed the ZipInputStream directly,
                    // it would be closed after the first file, preventing further iteration.
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buffer = new byte[4096];
                    int len;
                    while ((len = zis.read(buffer)) > 0) {
                        baos.write(buffer, 0, len);
                    }

                    // Create a new, independent input stream for the processor
                    InputStream entryInputStream = new ByteArrayInputStream(baos.toByteArray());

                    cdrProcessingExecutor.submitCdrStreamProcessing(
                            zipEntry.getName(),
                            entryInputStream,
                            CiscoCm60CdrProcessor.PLANT_TYPE_IDENTIFIER
                    );
                }
                // Close the current entry to move to the next one
                zis.closeEntry();
            }
        } catch (IOException e) {
            log.error("Error processing ZIP file: {}", zipFile.getOriginalFilename(), e);
            throw new RuntimeException("Failed to process ZIP file: " + zipFile.getOriginalFilename(), e);
        }
    }
}