package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import com.infomedia.abacox.telephonypricing.db.entity.CommunicationLocation;
import com.infomedia.abacox.telephonypricing.db.entity.ExtensionRange;
import com.infomedia.abacox.telephonypricing.db.entity.FileInfo;
import com.opencsv.CSVWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
@Log4j2
@RequiredArgsConstructor
public class TestCdrProcessingService {

    private final CdrProcessorService cdrProcessorService;
    private final CommunicationLocationLookupService commLocationLookupService;
    private final EmployeeLookupService employeeLookupService;
    private final CdrConfigService cdrConfigService;
    private final List<CdrProcessor> cdrProcessors;

    private static final AtomicLong DUMMY_FILE_ID_GENERATOR = new AtomicLong(-1);

    public ResponseEntity<StreamingResponseBody> processTestCdr(MultipartFile file, Long plantTypeId) {
        log.info("Starting TEST CDR processing for file: {}, PlantTypeID: {}", file.getOriginalFilename(), plantTypeId);

        // 1. Create Dummy FileInfo
        FileInfo dummyFileInfo = FileInfo.builder()
                .id(DUMMY_FILE_ID_GENERATOR.getAndDecrement())
                .filename(file.getOriginalFilename())
                .parentId(plantTypeId.intValue())
                .size((int) file.getSize())
                .date(LocalDateTime.now())
                .type("TEST_UPLOAD")
                .processingStatus(FileInfo.ProcessingStatus.PENDING)
                .build();

        List<ProcessedCdrResult> results = new ArrayList<>();

        // 2. Process the file in memory
        try (InputStream inputStream = file.getInputStream();
                InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
                BufferedReader bufferedReader = new BufferedReader(reader)) {

            Map<Long, ExtensionLimits> extensionLimits = employeeLookupService.getExtensionLimits();
            Map<Long, List<ExtensionRange>> extensionRanges = employeeLookupService.getExtensionRanges();
            CdrProcessor initialParser = getProcessorForPlantType(plantTypeId);
            Map<String, Integer> currentHeaderMap = null;

            String line;
            while ((line = bufferedReader.readLine()) != null) {
                String trimmedLine = line.trim();
                if (trimmedLine.isEmpty())
                    continue;

                if (currentHeaderMap == null && initialParser.isHeaderLine(trimmedLine)) {
                    currentHeaderMap = initialParser.parseHeader(trimmedLine);
                    continue;
                }

                // If no header found yet and this line is not header, we treat it as data or
                // fail if strict
                // For test, we attempt to proceed.

                // Pre-route
                CdrData preliminaryCdrData = initialParser.evaluateFormat(trimmedLine, null, null, currentHeaderMap);

                if (preliminaryCdrData == null) {
                    // Could be skipped or invalid format
                    continue;
                }

                // Routing
                Optional<CommunicationLocation> targetCommLocationOpt = commLocationLookupService
                        .findBestCommunicationLocation(
                                plantTypeId,
                                preliminaryCdrData.getCallingPartyNumber(),
                                preliminaryCdrData.getCallingPartyNumberPartition(),
                                preliminaryCdrData.getFinalCalledPartyNumber(),
                                preliminaryCdrData.getFinalCalledPartyNumberPartition(),
                                preliminaryCdrData.getLastRedirectDn(),
                                preliminaryCdrData.getLastRedirectDnPartition(),
                                preliminaryCdrData.getDateTimeOrigination());

                if (targetCommLocationOpt.isPresent()) {
                    CommunicationLocation targetCommLocation = targetCommLocationOpt.get();
                    CdrProcessor finalProcessor = getProcessorForPlantType(targetCommLocation.getPlantTypeId());

                    LineProcessingContext context = LineProcessingContext.builder()
                            .cdrLine(trimmedLine)
                            .commLocation(targetCommLocation)
                            .cdrProcessor(finalProcessor)
                            .extensionRanges(extensionRanges)
                            .extensionLimits(extensionLimits)
                            .fileInfo(dummyFileInfo)
                            .headerPositions(currentHeaderMap)
                            .build();

                    ProcessedCdrResult result = cdrProcessorService.processCdrData(context);
                    if (result.getOutcome() != ProcessingOutcome.SKIPPED) {
                        results.add(result);
                    }
                } else {
                    // Unroutable
                    CdrData failData = new CdrData();
                    failData.setRawCdrLine(trimmedLine);
                    ProcessedCdrResult failResult = ProcessedCdrResult.builder()
                            .cdrData(failData)
                            .outcome(ProcessingOutcome.QUARANTINED)
                            .errorType(QuarantineErrorType.PENDING_ASSOCIATION)
                            .errorMessage("Could not route to CommunicationLocation")
                            .errorStep("TestCdrRouting")
                            .build();
                    results.add(failResult);
                }
            }

        } catch (IOException e) {
            log.error("Error reading test file", e);
            throw new RuntimeException("Error processing test file", e);
        }

        // 3. Prepare ZIP Response
        return createZipResponse(results, file.getOriginalFilename(), dummyFileInfo.getId());
    }

    private ResponseEntity<StreamingResponseBody> createZipResponse(List<ProcessedCdrResult> results,
            String originalFilename, Long fileInfoId) {
        String baseName = originalFilename.contains(".")
                ? originalFilename.substring(0, originalFilename.lastIndexOf('.'))
                : originalFilename;
        String zipFilename = "processed_test_" + baseName + ".zip";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/zip"));
        headers.setContentDispositionFormData("attachment", zipFilename);

        StreamingResponseBody responseBody = outputStream -> {
            try (ZipOutputStream zos = new ZipOutputStream(outputStream)) {

                // 3.1 Call Records CSV
                ZipEntry callRecordsEntry = new ZipEntry("callrecords.csv");
                zos.putNextEntry(callRecordsEntry);
                writeCallRecordsCsv(zos, results, fileInfoId);
                zos.closeEntry();

                // 3.2 Failed Call Records CSV
                ZipEntry failedRecordsEntry = new ZipEntry("failedcallrecords.csv");
                zos.putNextEntry(failedRecordsEntry);
                writeFailedCallRecordsCsv(zos, results, fileInfoId);
                zos.closeEntry();

            } catch (Exception e) {
                log.error("Error generating ZIP response", e);
            }
        };

        return new ResponseEntity<>(responseBody, headers, HttpStatus.OK);
    }

    private void writeCallRecordsCsv(OutputStream os, List<ProcessedCdrResult> results, Long fileInfoId) {
        try {
            // Do NOT close these resources as they close the underlying ZipOutputStream
            OutputStreamWriter writer = new OutputStreamWriter(os, StandardCharsets.UTF_8);
            CSVWriter csvWriter = new CSVWriter(writer);

            // Header matching call_record table
            csvWriter.writeNext(new String[] {
                    "id", "dial", "comm_location_id", "service_date", "operator_id",
                    "employee_extension", "employee_auth_code", "indicator_id", "destination_phone",
                    "duration", "ring_count", "telephony_type_id", "billed_amount",
                    "price_per_minute", "initial_price", "is_incoming", "trunk",
                    "initial_trunk", "employee_id", "destination_employee_id", "employee_transfer",
                    "transfer_cause", "assignment_cause", "file_info_id", "ctl_hash"
            });

            for (ProcessedCdrResult result : results) {
                if (result.getOutcome() == ProcessingOutcome.SUCCESS && result.getCdrData() != null) {
                    CdrData data = result.getCdrData();

                    String commLocationId = result.getCommLocation() != null
                            ? String.valueOf(result.getCommLocation().getId())
                            : "";
                    String isIncoming = data.getCallDirection() == CallDirection.INCOMING ? "true" : "false";

                    csvWriter.writeNext(new String[] {
                            "", // id (auto-generated)
                            data.getEffectiveDestinationNumber(),
                            commLocationId,
                            String.valueOf(DateTimeUtil.convertToZone(data.getDateTimeOrigination(),
                                    ZoneId.systemDefault())),
                            String.valueOf(data.getOperatorId()),
                            data.getCallingPartyNumber(),
                            data.getAuthCodeDescription(),
                            String.valueOf(data.getIndicatorId()),
                            data.getOriginalFinalCalledPartyNumber(),
                            String.valueOf(data.getDurationSeconds()),
                            String.valueOf(data.getRingingTimeSeconds()),
                            String.valueOf(data.getTelephonyTypeId()),
                            String.valueOf(data.getBilledAmount()),
                            String.valueOf(data.getPricePerMinute()),
                            String.valueOf(data.getInitialPricePerMinute()),
                            isIncoming,
                            data.getDestDeviceName(),
                            data.getOrigDeviceName(),
                            String.valueOf(data.getEmployeeId()),
                            String.valueOf(data.getDestinationEmployeeId()),
                            data.getEmployeeTransferExtension(),
                            data.getTransferCause() != null ? String.valueOf(data.getTransferCause().getValue()) : "",
                            data.getAssignmentCause() != null ? String.valueOf(data.getAssignmentCause().getValue())
                                    : "",
                            String.valueOf(fileInfoId),
                            String.valueOf(data.getCtlHash())
                    });
                }
            }
            csvWriter.flush();
            writer.flush();
        } catch (IOException e) {
            log.error("Error writing call records CSV", e);
        }
    }

    private void writeFailedCallRecordsCsv(OutputStream os, List<ProcessedCdrResult> results, Long fileInfoId) {
        try {
            // Do NOT close these resources as they close the underlying ZipOutputStream
            OutputStreamWriter writer = new OutputStreamWriter(os, StandardCharsets.UTF_8);
            CSVWriter csvWriter = new CSVWriter(writer);

            // Header matching failed_call_record table
            csvWriter.writeNext(new String[] {
                    "id", "employee_extension", "error_type", "error_message",
                    "original_call_record_id", "processing_step", "file_info_id",
                    "comm_location_id", "ctl_hash"
            });

            for (ProcessedCdrResult result : results) {
                if (result.getOutcome() == ProcessingOutcome.QUARANTINED) {
                    CdrData data = result.getCdrData();
                    String commLocationId = result.getCommLocation() != null
                            ? String.valueOf(result.getCommLocation().getId())
                            : "";
                    String ctlHash = data != null ? String.valueOf(data.getCtlHash()) : "";
                    String employeeExtension = data != null ? data.getCallingPartyNumber() : "";

                    csvWriter.writeNext(new String[] {
                            "", // id
                            employeeExtension,
                            result.getErrorType() != null ? result.getErrorType().name() : "",
                            result.getErrorMessage(),
                            String.valueOf(result.getOriginalCallRecordId()),
                            result.getErrorStep(),
                            String.valueOf(fileInfoId),
                            commLocationId,
                            ctlHash
                    });
                }
            }
            csvWriter.flush();
            writer.flush();
        } catch (IOException e) {
            log.error("Error writing failed records CSV", e);
        }
    }

    private CdrProcessor getProcessorForPlantType(Long plantTypeId) {
        return cdrProcessors.stream()
                .filter(p -> p.getPlantTypeIdentifiers().contains(plantTypeId))
                .findFirst()
                .orElseThrow(
                        () -> new IllegalArgumentException("No CDR processor found for plant type ID: " + plantTypeId));
    }
}
