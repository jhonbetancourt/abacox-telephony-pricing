package com.infomedia.abacox.telephonypricing.controller;

import com.infomedia.abacox.telephonypricing.constants.DateTimePattern;
import com.infomedia.abacox.telephonypricing.db.view.CorporateReportView;
import com.infomedia.abacox.telephonypricing.dto.generic.PageableRequest;
import com.infomedia.abacox.telephonypricing.dto.report.*;
import com.infomedia.abacox.telephonypricing.dto.generic.ExcelRequest;
import com.infomedia.abacox.telephonypricing.dto.generic.FilterRequest;
import com.infomedia.abacox.telephonypricing.service.ReportService;
import com.turkraft.springfilter.boot.Filter;
import io.swagger.v3.oas.annotations.Parameter;
import org.springdoc.core.annotations.ParameterObject;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

@RequiredArgsConstructor
@RestController
@Tag(name = "Report", description = "Report API")
@SecurityRequirements({
        @SecurityRequirement(name = "JWT_Token"),
        @SecurityRequirement(name = "Username")
})
@RequestMapping("/api/report")
public class ReportController {

    private final ReportService reportService;

    @GetMapping(value = "corporateReport", produces = MediaType.APPLICATION_JSON_VALUE)
    public Page<CorporateReportDto> getCorporateReport(@Parameter(hidden = true) Pageable pageable
            , @Parameter(hidden = true) @Filter Specification<CorporateReportView> spec
            , @ParameterObject FilterRequest filterRequest) {
        return reportService.generateCorporateReport(spec, pageable);
    }

    @GetMapping(value = "corporateReport/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<Resource> exportExcelCorporateReport(@Parameter(hidden = true) Pageable pageable
            , @Parameter(hidden = true) @Filter Specification<CorporateReportView> spec
            , @ParameterObject FilterRequest filterRequest
            , @ParameterObject ExcelRequest excelRequest) {
        ByteArrayResource resource = reportService.exportExcelCorporateReport(spec
                , pageable, excelRequest.toExcelGeneratorBuilder());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=corporate_report.xlsx")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }

    @GetMapping(value = "employeeActivity", produces = MediaType.APPLICATION_JSON_VALUE)
    public Page<EmployeeActivityReportDto> getEmployeeActivityReport(@Parameter(hidden = true) Pageable pageable
            , @ParameterObject PageableRequest pageableRequest
            , @RequestParam(required = false) String employeeName, @RequestParam(required = false) String employeeExtension
            , @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate
            , @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate) {
        return reportService.generateEmployeeActivityReport(employeeName
                , employeeExtension, startDate, endDate, pageable);
    }

    @GetMapping(value = "employeeActivity/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<Resource> exportExcelEmployeeActivityReport(@Parameter(hidden = true) Pageable pageable
            , @RequestParam(required = false) String employeeName, @RequestParam(required = false) String employeeExtension
            , @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate
            , @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate
            , @ParameterObject PageableRequest pageableRequest
            , @ParameterObject ExcelRequest excelRequest) {

        ByteArrayResource resource = reportService.exportExcelEmployeeActivityReport(employeeName
                , employeeExtension, startDate, endDate, pageable, excelRequest.toExcelGeneratorBuilder());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=employee_activity_report.xlsx")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }

    @GetMapping(value = "employeeCall", produces = MediaType.APPLICATION_JSON_VALUE)
    public Page<EmployeeCallReportDto> getEmployeeCallReport(@Parameter(hidden = true) Pageable pageable
            , @ParameterObject PageableRequest pageableRequest
            , @RequestParam(required = false) String employeeName, @RequestParam(required = false) String employeeExtension
            , @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate
            , @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate) {
        return reportService.generateEmployeeCallReport(employeeName
                , employeeExtension, startDate, endDate, pageable);
    }

    @GetMapping(value = "employeeCall/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<Resource> exportExcelEmployeeCallReport(@Parameter(hidden = true) Pageable pageable
            , @RequestParam(required = false) String employeeName, @RequestParam(required = false) String employeeExtension
            , @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate
            , @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate
            , @ParameterObject ExcelRequest excelRequest
            , @ParameterObject PageableRequest pageableRequest) {

        ByteArrayResource resource = reportService.exportExcelEmployeeCallReport(employeeName
                , employeeExtension, startDate, endDate, pageable, excelRequest.toExcelGeneratorBuilder());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=employee_call_report.xlsx")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }

    @GetMapping(value = "unassignedCall", produces = MediaType.APPLICATION_JSON_VALUE)
    public Page<UnassignedCallReportDto> getUnassignedCallReport(@Parameter(hidden = true) Pageable pageable
            , @ParameterObject PageableRequest pageableRequest
            , @RequestParam(required = false) String extension
            , @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate
            , @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate) {
        return reportService.generateUnassignedCallReport(extension, startDate, endDate, pageable);
    }

    @GetMapping(value = "unassignedCall/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<Resource> exportExcelUnassignedCallReport(@Parameter(hidden = true) Pageable pageable
            , @RequestParam(required = false) String extension
            , @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate
            , @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate
            , @ParameterObject ExcelRequest excelRequest
            , @ParameterObject PageableRequest pageableRequest) {

        ByteArrayResource resource = reportService.exportExcelUnassignedCallReport(extension, startDate, endDate
                , pageable, excelRequest.toExcelGeneratorBuilder());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=unassigned_call_report.xlsx")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }

    @GetMapping(value = "processingFailure", produces = MediaType.APPLICATION_JSON_VALUE)
    public Page<ProcessingFailureReportDto> getProcessingFailureReport(@Parameter(hidden = true) Pageable pageable
            , @ParameterObject PageableRequest pageableRequest
            , @RequestParam(required = false) String directory
            , @RequestParam(required = false) String errorType
            , @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate
            , @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate) {
        return reportService.generateProcessingFailureReport(directory, errorType, startDate, endDate, pageable);
    }

    @GetMapping(value = "processingFailure/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<Resource> exportExcelProcessingFailureReport(@Parameter(hidden = true) Pageable pageable
            , @RequestParam(required = false) String directory
            , @RequestParam(required = false) String errorType
            , @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate
            , @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate
            , @ParameterObject ExcelRequest excelRequest
            , @ParameterObject PageableRequest pageableRequest) {

        ByteArrayResource resource = reportService.exportExcelProcessingFailureReport(directory, errorType, startDate
                , endDate, pageable, excelRequest.toExcelGeneratorBuilder());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=processing_failure_report.xlsx")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }

    @GetMapping(value = "missedCallEmployee", produces = MediaType.APPLICATION_JSON_VALUE)
    public Page<MissedCallEmployeeReportDto> getMissedCallEmployeeReport(@Parameter(hidden = true) Pageable pageable, @ParameterObject PageableRequest pageableRequest,
                                                                         @RequestParam(required = false) String employeeName,
                                                                         @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                                                                         @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate) {
        return reportService.generateMissedCallEmployeeReport(employeeName, startDate, endDate, pageable);
    }

    @GetMapping(value = "missedCallEmployee/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<Resource> exportExcelMissedCallEmployeeReport(@Parameter(hidden = true) Pageable pageable,
                                                                        @RequestParam(required = false) String employeeName,
                                                                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                                                                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate,
                                                                        @ParameterObject ExcelRequest excelRequest,
                                                                        @ParameterObject PageableRequest pageableRequest) {

        ByteArrayResource resource = reportService.exportExcelMissedCallEmployeeReport(employeeName, startDate, endDate,
                pageable, excelRequest.toExcelGeneratorBuilder());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=missed_call_employee_report.xlsx")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }

    @GetMapping(value = "unusedExtension", produces = MediaType.APPLICATION_JSON_VALUE)
    public Page<UnusedExtensionReportDto> getUnusedExtensionReport(@Parameter(hidden = true) Pageable pageable,
                                                                   @ParameterObject PageableRequest pageableRequest,
                                                                   @RequestParam(required = false) String employeeName,
                                                                   @RequestParam(required = false) String extension,
                                                                   @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                                                                   @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate) {
        return reportService.generateUnusedExtensionReport(employeeName, extension, startDate, endDate, pageable);
    }

    @GetMapping(value = "unusedExtension/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<Resource> exportExcelUnusedExtensionReport(@Parameter(hidden = true) Pageable pageable,
                                                                     @RequestParam(required = false) String employeeName,
                                                                     @RequestParam(required = false) String extension,
                                                                     @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                                                                     @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate,
                                                                     @ParameterObject ExcelRequest excelRequest,
                                                                     @ParameterObject PageableRequest pageableRequest) {

        ByteArrayResource resource = reportService.exportExcelUnusedExtensionReport(employeeName, extension, startDate,
                endDate, pageable, excelRequest.toExcelGeneratorBuilder());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=unused_extension_report.xlsx")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }

    @GetMapping(value = "extensionGroup", produces = MediaType.APPLICATION_JSON_VALUE)
    public Page<ExtensionGroupReportDto> getExtensionGroupReport(@Parameter(hidden = true) Pageable pageable,
                                                                 @ParameterObject PageableRequest pageableRequest,
                                                                 @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                                                                 @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate,
                                                                 @RequestParam(required = false) List<String> extensions,
                                                                 @RequestParam(required = false) List<Long> operatorIds,
                                                                 @RequestParam(required = false) String voicemailNumber) {
        return reportService.generateExtensionGroupReport(startDate, endDate, extensions, operatorIds, voicemailNumber, pageable);
    }

    @GetMapping(value = "extensionGroup/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<Resource> exportExcelExtensionGroupReport(@Parameter(hidden = true) Pageable pageable,
                                                                    @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                                                                    @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate,
                                                                    @RequestParam List<String> extensions,
                                                                    @RequestParam(required = false) List<Long> operatorIds,
                                                                    @RequestParam String voicemailNumber,
                                                                    @ParameterObject ExcelRequest excelRequest,
                                                                    @ParameterObject PageableRequest pageableRequest) {

        ByteArrayResource resource = reportService.exportExcelExtensionGroupReport(startDate, endDate, extensions,
                operatorIds, voicemailNumber, pageable, excelRequest.toExcelGeneratorBuilder());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=extension_group_report.xlsx")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }
}
