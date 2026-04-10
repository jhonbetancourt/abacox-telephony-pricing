package com.infomedia.abacox.telephonypricing.controller;

import com.infomedia.abacox.telephonypricing.constants.DateTimePattern;
import com.infomedia.abacox.telephonypricing.db.entity.CallRecord;
import com.infomedia.abacox.telephonypricing.db.entity.FailedCallRecord;
import com.infomedia.abacox.telephonypricing.db.view.CorporateReportView;
import com.infomedia.abacox.telephonypricing.dto.callrecord.CallRecordDto;
import com.infomedia.abacox.telephonypricing.dto.failedcallrecord.FailedCallRecordDto;
import com.infomedia.abacox.telephonypricing.dto.generic.PageableRequest;
import com.infomedia.abacox.telephonypricing.dto.report.*;
import com.infomedia.abacox.telephonypricing.dto.generic.ExcelRequest;
import com.infomedia.abacox.telephonypricing.dto.generic.ExportRequest;
import com.infomedia.abacox.telephonypricing.dto.generic.FilterRequest;
import com.infomedia.abacox.telephonypricing.model.report.UnassignedCallGroupingType;
import com.infomedia.abacox.telephonypricing.service.report.*;
import com.turkraft.springfilter.boot.Filter;
import io.swagger.v3.oas.annotations.Parameter;
import org.springdoc.core.annotations.ParameterObject;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Slice;
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
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.time.LocalDateTime;
import java.util.List;

@RequiredArgsConstructor
@RestController
@Tag(name = "Report", description = "Report API")
@SecurityRequirements(value = {
                @SecurityRequirement(name = "JWT_Token"),
                @SecurityRequirement(name = "Username"),
                @SecurityRequirement(name = "Tenant_Id")
})
@RequestMapping("/api/report")
public class ReportController {

        private final CallRecordReportService callRecordReportService;
        private final EmployeeReportService employeeReportService;
        private final TelephonyUsageReportService telephonyUsageReportService;
        private final SubdivisionReportService subdivisionReportService;
        private final ExtensionReportService extensionReportService;
        private final ConferenceReportService conferenceReportService;
        private final ExtensionGroupReportService extensionGroupReportService;

        // ── failedCallRecords ──

        @GetMapping(value = "failedCallRecords", produces = MediaType.APPLICATION_JSON_VALUE)
        public Slice<FailedCallRecordDto> getFailedCallRecords(@Parameter(hidden = true) Pageable pageable,
                        @Parameter(hidden = true) @Filter Specification<FailedCallRecord> spec,
                        @ParameterObject FilterRequest filterRequest) {
                return callRecordReportService.generateFailedCallRecordsReport(spec, pageable);
        }

        @GetMapping(value = "failedCallRecords/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
        public ResponseEntity<StreamingResponseBody> exportExcelFailedCallRecords(
                        @Parameter(hidden = true) @Filter Specification<FailedCallRecord> spec,
                        @ParameterObject FilterRequest filterRequest,
                        @ParameterObject ExportRequest exportRequest,
                        @ParameterObject ExcelRequest excelRequest) {
                StreamingResponseBody body = out ->
                        callRecordReportService.exportExcelFailedCallRecordsReport(spec,
                                exportRequest.getSortOrder(), exportRequest.getMaxRows(),
                                out, excelRequest.toExcelGeneratorBuilder());
                return ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION,
                                                "attachment; filename=failed_call_records.xlsx")
                                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                                .body(body);
        }

        // ── callRecords ──

        @GetMapping(value = "callRecords", produces = MediaType.APPLICATION_JSON_VALUE)
        public Slice<CallRecordDto> getCallRecords(@Parameter(hidden = true) Pageable pageable,
                        @Parameter(hidden = true) @Filter Specification<CallRecord> spec,
                        @ParameterObject FilterRequest filterRequest) {
                return callRecordReportService.generateCallRecordsReport(spec, pageable);
        }

        @GetMapping(value = "callRecords/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
        public ResponseEntity<StreamingResponseBody> exportExcelCallRecords(
                        @Parameter(hidden = true) @Filter Specification<CallRecord> spec,
                        @ParameterObject FilterRequest filterRequest,
                        @ParameterObject ExportRequest exportRequest,
                        @ParameterObject ExcelRequest excelRequest) {
                StreamingResponseBody body = out ->
                        callRecordReportService.exportExcelCallRecordsReport(spec,
                                exportRequest.getSortOrder(), exportRequest.getMaxRows(),
                                out, excelRequest.toExcelGeneratorBuilder());
                return ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=call_records.xlsx")
                                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                                .body(body);
        }

        // ── corporateReport ──

        @GetMapping(value = "corporateReport", produces = MediaType.APPLICATION_JSON_VALUE)
        public Slice<CorporateReportDto> getCorporateReport(@Parameter(hidden = true) Pageable pageable,
                        @Parameter(hidden = true) @Filter Specification<CorporateReportView> spec,
                        @ParameterObject FilterRequest filterRequest) {
                return callRecordReportService.generateCorporateReport(spec, pageable);
        }

        @GetMapping(value = "corporateReport/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
        public ResponseEntity<StreamingResponseBody> exportExcelCorporateReport(
                        @Parameter(hidden = true) @Filter Specification<CorporateReportView> spec,
                        @ParameterObject FilterRequest filterRequest,
                        @ParameterObject ExportRequest exportRequest,
                        @ParameterObject ExcelRequest excelRequest) {
                StreamingResponseBody body = out ->
                        callRecordReportService.exportExcelCorporateReport(spec,
                                exportRequest.getSortOrder(), exportRequest.getMaxRows(),
                                out, excelRequest.toExcelGeneratorBuilder());
                return ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=corporate_report.xlsx")
                                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                                .body(body);
        }

        // ── employeeActivity ──

        @GetMapping(value = "employeeActivity", produces = MediaType.APPLICATION_JSON_VALUE)
        public Slice<EmployeeActivityReportDto> getEmployeeActivityReport(@Parameter(hidden = true) Pageable pageable,
                        @ParameterObject PageableRequest pageableRequest,
                        @RequestParam(required = false) String employeeName,
                        @RequestParam(required = false) String employeeExtension,
                        @RequestParam(required = false) Long subdivisionId,
                        @RequestParam(required = false) Long costCenterId,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate) {
                return employeeReportService.generateEmployeeActivityReport(employeeName, employeeExtension,
                                subdivisionId, costCenterId, startDate, endDate, pageable);
        }

        @GetMapping(value = "employeeActivity/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
        public ResponseEntity<StreamingResponseBody> exportExcelEmployeeActivityReport(
                        @RequestParam(required = false) String employeeName,
                        @RequestParam(required = false) String employeeExtension,
                        @RequestParam(required = false) Long subdivisionId,
                        @RequestParam(required = false) Long costCenterId,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate,
                        @ParameterObject ExportRequest exportRequest,
                        @ParameterObject ExcelRequest excelRequest) {
                StreamingResponseBody body = out ->
                        employeeReportService.exportExcelEmployeeActivityReport(employeeName,
                                employeeExtension, subdivisionId, costCenterId,
                                startDate, endDate,
                                exportRequest.getSortOrder(), exportRequest.getMaxRows(),
                                out, excelRequest.toExcelGeneratorBuilder());
                return ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION,
                                                "attachment; filename=employee_activity_report.xlsx")
                                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                                .body(body);
        }

        // ── employeeCall ──

        @GetMapping(value = "employeeCall", produces = MediaType.APPLICATION_JSON_VALUE)
        public Slice<EmployeeCallReportDto> getEmployeeCallReport(@Parameter(hidden = true) Pageable pageable,
                        @ParameterObject PageableRequest pageableRequest,
                        @RequestParam(required = false) String employeeName,
                        @RequestParam(required = false) String employeeExtension,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate) {
                return employeeReportService.generateEmployeeCallReport(employeeName, employeeExtension, startDate,
                                endDate,
                                pageable);
        }

        @GetMapping(value = "employeeCall/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
        public ResponseEntity<StreamingResponseBody> exportExcelEmployeeCallReport(
                        @RequestParam(required = false) String employeeName,
                        @RequestParam(required = false) String employeeExtension,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate,
                        @ParameterObject ExportRequest exportRequest,
                        @ParameterObject ExcelRequest excelRequest) {
                StreamingResponseBody body = out ->
                        employeeReportService.exportExcelEmployeeCallReport(employeeName,
                                employeeExtension, startDate, endDate,
                                exportRequest.getSortOrder(), exportRequest.getMaxRows(),
                                out, excelRequest.toExcelGeneratorBuilder());
                return ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION,
                                                "attachment; filename=employee_call_report.xlsx")
                                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                                .body(body);
        }

        // ── unassignedCall ──

        @GetMapping(value = "unassignedCall", produces = MediaType.APPLICATION_JSON_VALUE)
        public Slice<UnassignedCallReportDto> getUnassignedCallReport(@Parameter(hidden = true) Pageable pageable,
                        @ParameterObject PageableRequest pageableRequest,
                        @RequestParam(required = false) String extension,
                        @RequestParam(required = false, defaultValue = "EXTENSION") UnassignedCallGroupingType groupingType,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate) {
                return callRecordReportService.generateUnassignedCallReport(extension, groupingType, startDate, endDate,
                                pageable);
        }

        @GetMapping(value = "unassignedCall/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
        public ResponseEntity<StreamingResponseBody> exportExcelUnassignedCallReport(
                        @RequestParam(required = false) String extension,
                        @RequestParam(required = false, defaultValue = "EXTENSION") UnassignedCallGroupingType groupingType,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate,
                        @ParameterObject ExportRequest exportRequest,
                        @ParameterObject ExcelRequest excelRequest) {
                StreamingResponseBody body = out ->
                        callRecordReportService.exportExcelUnassignedCallReport(extension, groupingType,
                                startDate, endDate,
                                exportRequest.getSortOrder(), exportRequest.getMaxRows(),
                                out, excelRequest.toExcelGeneratorBuilder());
                return ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION,
                                                "attachment; filename=unassigned_call_report.xlsx")
                                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                                .body(body);
        }

        // ── processingFailure ──

        @GetMapping(value = "processingFailure", produces = MediaType.APPLICATION_JSON_VALUE)
        public Slice<ProcessingFailureReportDto> getProcessingFailureReport(@Parameter(hidden = true) Pageable pageable,
                        @ParameterObject PageableRequest pageableRequest,
                        @RequestParam(required = false) String directory,
                        @RequestParam(required = false) String errorType,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate) {
                return callRecordReportService.generateProcessingFailureReport(directory, errorType, startDate, endDate,
                                pageable);
        }

        @GetMapping(value = "processingFailure/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
        public ResponseEntity<StreamingResponseBody> exportExcelProcessingFailureReport(
                        @RequestParam(required = false) String directory,
                        @RequestParam(required = false) String errorType,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate,
                        @ParameterObject ExportRequest exportRequest,
                        @ParameterObject ExcelRequest excelRequest) {
                StreamingResponseBody body = out ->
                        callRecordReportService.exportExcelProcessingFailureReport(directory, errorType,
                                startDate, endDate,
                                exportRequest.getSortOrder(), exportRequest.getMaxRows(),
                                out, excelRequest.toExcelGeneratorBuilder());
                return ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION,
                                                "attachment; filename=processing_failure_report.xlsx")
                                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                                .body(body);
        }

        // ── missedCallEmployee ──

        @GetMapping(value = "missedCallEmployee", produces = MediaType.APPLICATION_JSON_VALUE)
        public Slice<MissedCallEmployeeReportDto> getMissedCallEmployeeReport(
                        @Parameter(hidden = true) Pageable pageable, @ParameterObject PageableRequest pageableRequest,
                        @RequestParam(required = false) String employeeName,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate,
                        @RequestParam(required = false, defaultValue = "15") Integer minRingCount) {
                return employeeReportService.generateMissedCallEmployeeReport(employeeName, startDate, endDate,
                                minRingCount, pageable);
        }

        @GetMapping(value = "missedCallEmployee/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
        public ResponseEntity<StreamingResponseBody> exportExcelMissedCallEmployeeReport(
                        @RequestParam(required = false) String employeeName,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate,
                        @RequestParam(required = false, defaultValue = "15") Integer minRingCount,
                        @ParameterObject ExportRequest exportRequest,
                        @ParameterObject ExcelRequest excelRequest) {
                StreamingResponseBody body = out ->
                        employeeReportService.exportExcelMissedCallEmployeeReport(employeeName, startDate, endDate,
                                minRingCount,
                                exportRequest.getSortOrder(), exportRequest.getMaxRows(),
                                out, excelRequest.toExcelGeneratorBuilder());
                return ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION,
                                                "attachment; filename=missed_call_employee_report.xlsx")
                                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                                .body(body);
        }

        // ── unusedExtension ──

        @GetMapping(value = "unusedExtension", produces = MediaType.APPLICATION_JSON_VALUE)
        public Slice<UnusedExtensionReportDto> getUnusedExtensionReport(@Parameter(hidden = true) Pageable pageable,
                        @ParameterObject PageableRequest pageableRequest,
                        @RequestParam(required = false) String employeeName,
                        @RequestParam(required = false) String extension,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate) {
                return extensionReportService.generateUnusedExtensionReport(employeeName, extension, startDate, endDate,
                                pageable);
        }

        @GetMapping(value = "unusedExtension/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
        public ResponseEntity<StreamingResponseBody> exportExcelUnusedExtensionReport(
                        @RequestParam(required = false) String employeeName,
                        @RequestParam(required = false) String extension,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate,
                        @ParameterObject ExportRequest exportRequest,
                        @ParameterObject ExcelRequest excelRequest) {
                StreamingResponseBody body = out ->
                        extensionReportService.exportExcelUnusedExtensionReport(employeeName, extension,
                                startDate, endDate,
                                exportRequest.getSortOrder(), exportRequest.getMaxRows(),
                                out, excelRequest.toExcelGeneratorBuilder());
                return ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION,
                                                "attachment; filename=unused_extension_report.xlsx")
                                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                                .body(body);
        }

        // ── subdivisionUsage ──

        @GetMapping(value = "subdivisionUsage", produces = MediaType.APPLICATION_JSON_VALUE)
        public Slice<SubdivisionUsageReportDto> getSubdivisionUsageReport(@Parameter(hidden = true) Pageable pageable,
                        @ParameterObject PageableRequest pageableRequest,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate,
                        @RequestParam(required = false) Long parentSubdivisionId) {
                return subdivisionReportService.generateSubdivisionUsageReport(startDate, endDate, parentSubdivisionId,
                                pageable);
        }

        @GetMapping(value = "subdivisionUsage/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
        public ResponseEntity<StreamingResponseBody> exportExcelSubdivisionUsageReport(
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate,
                        @RequestParam(required = false) Long parentSubdivisionId,
                        @ParameterObject ExportRequest exportRequest,
                        @ParameterObject ExcelRequest excelRequest) {
                StreamingResponseBody body = out ->
                        subdivisionReportService.exportExcelSubdivisionUsageReport(startDate, endDate,
                                parentSubdivisionId,
                                exportRequest.getSortOrder(), exportRequest.getMaxRows(),
                                out, excelRequest.toExcelGeneratorBuilder());
                return ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION,
                                                "attachment; filename=subdivision_usage_report.xlsx")
                                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                                .body(body);
        }

        // ── subdivisionUsageByType ──

        @GetMapping(value = "subdivisionUsageByType", produces = MediaType.APPLICATION_JSON_VALUE)
        public Slice<SubdivisionUsageByTypeReportDto> getSubdivisionUsageByTypeReport(
                        @Parameter(hidden = true) Pageable pageable, @ParameterObject PageableRequest pageableRequest,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate,
                        @RequestParam(required = false) Long parentSubdivisionId) {
                return subdivisionReportService.generateSubdivisionUsageByTypeReport(startDate, endDate,
                                parentSubdivisionId, pageable);
        }

        @GetMapping(value = "subdivisionUsageByType/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
        public ResponseEntity<StreamingResponseBody> exportExcelSubdivisionUsageByTypeReport(
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate,
                        @RequestParam(required = false) Long parentSubdivisionId,
                        @ParameterObject ExportRequest exportRequest,
                        @ParameterObject ExcelRequest excelRequest) {
                StreamingResponseBody body = out ->
                        subdivisionReportService.exportExcelSubdivisionUsageByTypeReport(startDate, endDate,
                                parentSubdivisionId,
                                exportRequest.getSortOrder(), exportRequest.getMaxRows(),
                                out, excelRequest.toExcelGeneratorBuilder());
                return ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION,
                                                "attachment; filename=subdivision_usage_by_type_report.xlsx")
                                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                                .body(body);
        }

        // ── telephonyTypeUsage ──

        @GetMapping(value = "telephonyTypeUsage", produces = MediaType.APPLICATION_JSON_VALUE)
        public Slice<TelephonyTypeUsageGroupDto> getTelephonyTypeUsageReport(
                        @Parameter(hidden = true) Pageable pageable, @ParameterObject PageableRequest pageableRequest,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate) {
                return telephonyUsageReportService.generateTelephonyTypeUsageReport(startDate, endDate, pageable);
        }

        @GetMapping(value = "telephonyTypeUsage/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
        public ResponseEntity<StreamingResponseBody> exportExcelTelephonyTypeUsageReport(
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate,
                        @ParameterObject ExcelRequest excelRequest) {
                StreamingResponseBody body = out ->
                        telephonyUsageReportService.exportExcelTelephonyTypeUsageReport(startDate, endDate,
                                out, excelRequest.toExcelGeneratorBuilder());
                return ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION,
                                                "attachment; filename=telephony_type_usage_report.xlsx")
                                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                                .body(body);
        }

        // ── monthlyTelephonyTypeUsage ──

        @GetMapping(value = "monthlyTelephonyTypeUsage", produces = MediaType.APPLICATION_JSON_VALUE)
        public Slice<MonthlyTelephonyTypeUsageGroupDto> getMonthlyTelephonyTypeUsageReport(
                        @Parameter(hidden = true) Pageable pageable, @ParameterObject PageableRequest pageableRequest,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate) {
                return telephonyUsageReportService.generateMonthlyTelephonyTypeUsageReport(startDate, endDate,
                                pageable);
        }

        @GetMapping(value = "monthlyTelephonyTypeUsage/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
        public ResponseEntity<StreamingResponseBody> exportExcelMonthlyTelephonyTypeUsageReport(
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate,
                        @ParameterObject ExcelRequest excelRequest) {
                StreamingResponseBody body = out ->
                        telephonyUsageReportService.exportExcelMonthlyTelephonyTypeUsageReport(startDate, endDate,
                                out, excelRequest.toExcelGeneratorBuilder());
                return ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION,
                                                "attachment; filename=monthly_telephony_type_usage_report.xlsx")
                                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                                .body(body);
        }

        // ── costCenterUsage ──

        @GetMapping(value = "costCenterUsage", produces = MediaType.APPLICATION_JSON_VALUE)
        public Slice<CostCenterUsageReportDto> getCostCenterUsageReport(
                        @Parameter(hidden = true) Pageable pageable,
                        @ParameterObject PageableRequest pageableRequest,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate,
                        @RequestParam(required = false) Long parentCostCenterId) {
                return telephonyUsageReportService.generateCostCenterUsageReport(startDate, endDate, parentCostCenterId,
                                pageable);
        }

        @GetMapping(value = "costCenterUsage/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
        public ResponseEntity<StreamingResponseBody> exportExcelCostCenterUsageReport(
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate,
                        @RequestParam(required = false) Long parentCostCenterId,
                        @ParameterObject ExcelRequest excelRequest) {
                StreamingResponseBody body = out ->
                        telephonyUsageReportService.exportExcelCostCenterUsageReport(startDate, endDate,
                                parentCostCenterId, out, excelRequest.toExcelGeneratorBuilder());
                return ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION,
                                                "attachment; filename=cost_center_usage_report.xlsx")
                                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                                .body(body);
        }

        // ── employeeAuthCodeUsage ──

        @GetMapping(value = "employeeAuthCodeUsage", produces = MediaType.APPLICATION_JSON_VALUE)
        public Slice<EmployeeAuthCodeUsageReportDto> getEmployeeAuthCodeUsageReport(
                        @Parameter(hidden = true) Pageable pageable, @ParameterObject PageableRequest pageableRequest,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate) {
                return employeeReportService.generateEmployeeAuthCodeUsageReport(startDate, endDate, pageable);
        }

        @GetMapping(value = "employeeAuthCodeUsage/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
        public ResponseEntity<StreamingResponseBody> exportExcelEmployeeAuthCodeUsageReport(
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate,
                        @ParameterObject ExportRequest exportRequest,
                        @ParameterObject ExcelRequest excelRequest) {
                StreamingResponseBody body = out ->
                        employeeReportService.exportExcelEmployeeAuthCodeUsageReport(startDate, endDate,
                                exportRequest.getSortOrder(), exportRequest.getMaxRows(),
                                out, excelRequest.toExcelGeneratorBuilder());
                return ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION,
                                                "attachment; filename=employee_auth_code_usage_report.xlsx")
                                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                                .body(body);
        }

        // ── monthlySubdivisionUsage ──

        @GetMapping(value = "monthlySubdivisionUsage", produces = MediaType.APPLICATION_JSON_VALUE)
        public Slice<MonthlySubdivisionUsageReportDto> getMonthlySubdivisionUsageReport(
                        @Parameter(hidden = true) Pageable pageable, @ParameterObject PageableRequest pageableRequest,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate,
                        @RequestParam(required = false) List<Long> subdivisionIds) {
                return subdivisionReportService.generateMonthlySubdivisionUsageReport(startDate, endDate,
                                subdivisionIds,
                                pageable);
        }

        @GetMapping(value = "monthlySubdivisionUsage/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
        public ResponseEntity<StreamingResponseBody> exportExcelMonthlySubdivisionUsageReport(
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate,
                        @RequestParam(required = false) List<Long> subdivisionIds,
                        @ParameterObject ExcelRequest excelRequest) {
                StreamingResponseBody body = out ->
                        subdivisionReportService.exportExcelMonthlySubdivisionUsageReport(startDate, endDate,
                                subdivisionIds, out, excelRequest.toExcelGeneratorBuilder());
                return ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION,
                                                "attachment; filename=monthly_subdivision_usage_report.xlsx")
                                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                                .body(body);
        }

        // ── dialedNumberUsage ──

        @GetMapping(value = "dialedNumberUsage", produces = MediaType.APPLICATION_JSON_VALUE)
        public Slice<DialedNumberUsageReportDto> getDialedNumberUsageReport(@Parameter(hidden = true) Pageable pageable,
                        @ParameterObject PageableRequest pageableRequest,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate) {
                return telephonyUsageReportService.generateDialedNumberUsageReport(startDate, endDate, pageable);
        }

        @GetMapping(value = "dialedNumberUsage/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
        public ResponseEntity<StreamingResponseBody> exportExcelDialedNumberUsageReport(
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate,
                        @ParameterObject ExportRequest exportRequest,
                        @ParameterObject ExcelRequest excelRequest) {
                StreamingResponseBody body = out ->
                        telephonyUsageReportService.exportExcelDialedNumberUsageReport(startDate, endDate,
                                exportRequest.getSortOrder(), exportRequest.getMaxRows(),
                                out, excelRequest.toExcelGeneratorBuilder());
                return ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION,
                                                "attachment; filename=dialed_number_usage_report.xlsx")
                                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                                .body(body);
        }

        // ── destinationUsage ──

        @GetMapping(value = "destinationUsage", produces = MediaType.APPLICATION_JSON_VALUE)
        public Slice<DestinationUsageReportDto> getDestinationUsageReport(@Parameter(hidden = true) Pageable pageable,
                        @ParameterObject PageableRequest pageableRequest,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate) {
                return telephonyUsageReportService.generateDestinationUsageReport(startDate, endDate, pageable);
        }

        @GetMapping(value = "destinationUsage/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
        public ResponseEntity<StreamingResponseBody> exportExcelDestinationUsageReport(
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate,
                        @ParameterObject ExportRequest exportRequest,
                        @ParameterObject ExcelRequest excelRequest) {
                StreamingResponseBody body = out ->
                        telephonyUsageReportService.exportExcelDestinationUsageReport(startDate, endDate,
                                exportRequest.getSortOrder(), exportRequest.getMaxRows(),
                                out, excelRequest.toExcelGeneratorBuilder());
                return ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION,
                                                "attachment; filename=destination_usage_report.xlsx")
                                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                                .body(body);
        }

        // ── highestConsumptionEmployee ──

        @GetMapping(value = "highestConsumptionEmployee", produces = MediaType.APPLICATION_JSON_VALUE)
        public Slice<HighestConsumptionEmployeeReportDto> getHighestConsumptionEmployeeReport(
                        @Parameter(hidden = true) Pageable pageable, @ParameterObject PageableRequest pageableRequest,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate) {
                return employeeReportService.generateHighestConsumptionEmployeeReport(startDate, endDate, pageable);
        }

        @GetMapping(value = "highestConsumptionEmployee/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
        public ResponseEntity<StreamingResponseBody> exportExcelHighestConsumptionEmployeeReport(
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate,
                        @ParameterObject ExportRequest exportRequest,
                        @ParameterObject ExcelRequest excelRequest) {
                StreamingResponseBody body = out ->
                        employeeReportService.exportExcelHighestConsumptionEmployeeReport(startDate, endDate,
                                exportRequest.getSortOrder(), exportRequest.getMaxRows(),
                                out, excelRequest.toExcelGeneratorBuilder());
                return ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION,
                                                "attachment; filename=highest_consumption_employee_report.xlsx")
                                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                                .body(body);
        }

        // ── conferenceCalls ──

        @GetMapping(value = "conferenceCalls", produces = MediaType.APPLICATION_JSON_VALUE)
        public Slice<ConferenceGroupDto> getConferenceCallReport(@Parameter(hidden = true) Pageable pageable,
                        @ParameterObject PageableRequest pageableRequest,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate,
                        @RequestParam(required = false) String extension,
                        @RequestParam(required = false) String employeeName) {
                return conferenceReportService.generateConferenceCallsReport(startDate, endDate, extension,
                                employeeName, pageable);
        }

        @GetMapping(value = "conferenceCalls/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
        public ResponseEntity<StreamingResponseBody> exportExcelConferenceCallReport(
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate,
                        @RequestParam(required = false) String extension,
                        @RequestParam(required = false) String employeeName,
                        @ParameterObject ExcelRequest excelRequest) {
                StreamingResponseBody body = out ->
                        conferenceReportService.exportExcelConferenceCallsReport(startDate, endDate,
                                extension, employeeName, out, excelRequest.toExcelGeneratorBuilder());
                return ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=conference_calls.xlsx")
                                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                                .body(body);
        }

        // ── extensionGroup ──

        @GetMapping(value = "extensionGroup", produces = MediaType.APPLICATION_JSON_VALUE)
        public Slice<ExtensionGroupDto> getExtensionGroupReport(
                        @Parameter(hidden = true) Pageable pageable,
                        @ParameterObject PageableRequest pageableRequest,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate,
                        @RequestParam(required = false) Long groupId,
                        @RequestParam(required = false, defaultValue = "") String voicemailNumber,
                        @RequestParam(required = false) List<Long> operatorIds) {
                return extensionGroupReportService.generateExtensionGroupReport(
                                startDate, endDate, groupId, voicemailNumber, operatorIds, pageable);
        }
}
