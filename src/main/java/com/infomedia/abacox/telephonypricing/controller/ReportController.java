package com.infomedia.abacox.telephonypricing.controller;

import com.infomedia.abacox.telephonypricing.constants.DateTimePattern;
import com.infomedia.abacox.telephonypricing.db.entity.CallRecord;
import com.infomedia.abacox.telephonypricing.db.entity.FailedCallRecord;
import com.infomedia.abacox.telephonypricing.db.view.CorporateReportView;
import com.infomedia.abacox.telephonypricing.dto.callrecord.CallRecordDto;
import com.infomedia.abacox.telephonypricing.dto.failedcallrecord.FailedCallRecordDto;
import com.infomedia.abacox.telephonypricing.dto.generic.PageableRequest;
import com.infomedia.abacox.telephonypricing.dto.generic.SliceWithSummaries;
import com.infomedia.abacox.telephonypricing.dto.report.*;
import com.infomedia.abacox.telephonypricing.dto.generic.ExcelRequest;
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

        @GetMapping(value = "failedCallRecords", produces = MediaType.APPLICATION_JSON_VALUE)
        public Slice<FailedCallRecordDto> getFailedCallRecords(@Parameter(hidden = true) Pageable pageable,
                        @Parameter(hidden = true) @Filter Specification<FailedCallRecord> spec,
                        @ParameterObject FilterRequest filterRequest) {
                return callRecordReportService.generateFailedCallRecordsReport(spec, pageable);
        }

        @GetMapping(value = "failedCallRecords/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
        public ResponseEntity<StreamingResponseBody> exportExcelFailedCallRecords(@Parameter(hidden = true) Pageable pageable,
                        @Parameter(hidden = true) @Filter Specification<FailedCallRecord> spec,
                        @ParameterObject FilterRequest filterRequest, @ParameterObject ExcelRequest excelRequest) {
                StreamingResponseBody body = out ->
                        callRecordReportService.exportExcelFailedCallRecordsReport(spec, pageable, out,
                                excelRequest.toExcelGeneratorBuilder());
                return ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION,
                                                "attachment; filename=failed_call_records.xlsx")
                                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                                .body(body);
        }

        @GetMapping(value = "callRecords", produces = MediaType.APPLICATION_JSON_VALUE)
        public Slice<CallRecordDto> getCallRecords(@Parameter(hidden = true) Pageable pageable,
                        @Parameter(hidden = true) @Filter Specification<CallRecord> spec,
                        @ParameterObject FilterRequest filterRequest) {
                return callRecordReportService.generateCallRecordsReport(spec, pageable);
        }

        @GetMapping(value = "callRecords/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
        public ResponseEntity<StreamingResponseBody> exportExcelCallRecords(@Parameter(hidden = true) Pageable pageable,
                        @Parameter(hidden = true) @Filter Specification<CallRecord> spec,
                        @ParameterObject FilterRequest filterRequest, @ParameterObject ExcelRequest excelRequest) {
                StreamingResponseBody body = out ->
                        callRecordReportService.exportExcelCallRecordsReport(spec, pageable, out,
                                excelRequest.toExcelGeneratorBuilder());
                return ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=call_records.xlsx")
                                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                                .body(body);
        }

        @GetMapping(value = "corporateReport", produces = MediaType.APPLICATION_JSON_VALUE)
        public Slice<CorporateReportDto> getCorporateReport(@Parameter(hidden = true) Pageable pageable,
                        @Parameter(hidden = true) @Filter Specification<CorporateReportView> spec,
                        @ParameterObject FilterRequest filterRequest) {
                return callRecordReportService.generateCorporateReport(spec, pageable);
        }

        @GetMapping(value = "corporateReport/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
        public ResponseEntity<StreamingResponseBody> exportExcelCorporateReport(@Parameter(hidden = true) Pageable pageable,
                        @Parameter(hidden = true) @Filter Specification<CorporateReportView> spec,
                        @ParameterObject FilterRequest filterRequest, @ParameterObject ExcelRequest excelRequest) {
                StreamingResponseBody body = out ->
                        callRecordReportService.exportExcelCorporateReport(spec, pageable, out,
                                excelRequest.toExcelGeneratorBuilder());
                return ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=corporate_report.xlsx")
                                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                                .body(body);
        }

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
        public ResponseEntity<StreamingResponseBody> exportExcelEmployeeActivityReport(@Parameter(hidden = true) Pageable pageable,
                        @RequestParam(required = false) String employeeName,
                        @RequestParam(required = false) String employeeExtension,
                        @RequestParam(required = false) Long subdivisionId,
                        @RequestParam(required = false) Long costCenterId,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate,
                        @ParameterObject PageableRequest pageableRequest, @ParameterObject ExcelRequest excelRequest) {
                StreamingResponseBody body = out ->
                        employeeReportService.exportExcelEmployeeActivityReport(employeeName,
                                employeeExtension, subdivisionId, costCenterId,
                                startDate, endDate, pageable, out, excelRequest.toExcelGeneratorBuilder());
                return ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION,
                                                "attachment; filename=employee_activity_report.xlsx")
                                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                                .body(body);
        }

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
        public ResponseEntity<StreamingResponseBody> exportExcelEmployeeCallReport(@Parameter(hidden = true) Pageable pageable,
                        @RequestParam(required = false) String employeeName,
                        @RequestParam(required = false) String employeeExtension,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate,
                        @ParameterObject ExcelRequest excelRequest, @ParameterObject PageableRequest pageableRequest) {
                StreamingResponseBody body = out ->
                        employeeReportService.exportExcelEmployeeCallReport(employeeName,
                                employeeExtension, startDate, endDate, pageable, out,
                                excelRequest.toExcelGeneratorBuilder());
                return ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION,
                                                "attachment; filename=employee_call_report.xlsx")
                                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                                .body(body);
        }

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
        public ResponseEntity<StreamingResponseBody> exportExcelUnassignedCallReport(@Parameter(hidden = true) Pageable pageable,
                        @RequestParam(required = false) String extension,
                        @RequestParam(required = false, defaultValue = "EXTENSION") UnassignedCallGroupingType groupingType,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate,
                        @ParameterObject ExcelRequest excelRequest, @ParameterObject PageableRequest pageableRequest) {
                StreamingResponseBody body = out ->
                        callRecordReportService.exportExcelUnassignedCallReport(extension, groupingType,
                                startDate, endDate, pageable, out, excelRequest.toExcelGeneratorBuilder());
                return ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION,
                                                "attachment; filename=unassigned_call_report.xlsx")
                                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                                .body(body);
        }

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
        public ResponseEntity<StreamingResponseBody> exportExcelProcessingFailureReport(@Parameter(hidden = true) Pageable pageable,
                        @RequestParam(required = false) String directory,
                        @RequestParam(required = false) String errorType,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate,
                        @ParameterObject ExcelRequest excelRequest, @ParameterObject PageableRequest pageableRequest) {
                StreamingResponseBody body = out ->
                        callRecordReportService.exportExcelProcessingFailureReport(directory, errorType,
                                startDate, endDate, pageable, out, excelRequest.toExcelGeneratorBuilder());
                return ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION,
                                                "attachment; filename=processing_failure_report.xlsx")
                                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                                .body(body);
        }

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
        public ResponseEntity<StreamingResponseBody> exportExcelMissedCallEmployeeReport(@Parameter(hidden = true) Pageable pageable,
                        @RequestParam(required = false) String employeeName,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate,
                        @RequestParam(required = false, defaultValue = "15") Integer minRingCount,
                        @ParameterObject ExcelRequest excelRequest,
                        @ParameterObject PageableRequest pageableRequest) {
                StreamingResponseBody body = out ->
                        employeeReportService.exportExcelMissedCallEmployeeReport(employeeName, startDate, endDate,
                                minRingCount, pageable, out, excelRequest.toExcelGeneratorBuilder());
                return ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION,
                                                "attachment; filename=missed_call_employee_report.xlsx")
                                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                                .body(body);
        }

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
        public ResponseEntity<StreamingResponseBody> exportExcelUnusedExtensionReport(@Parameter(hidden = true) Pageable pageable,
                        @RequestParam(required = false) String employeeName,
                        @RequestParam(required = false) String extension,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate,
                        @ParameterObject ExcelRequest excelRequest,
                        @ParameterObject PageableRequest pageableRequest) {
                StreamingResponseBody body = out ->
                        extensionReportService.exportExcelUnusedExtensionReport(employeeName, extension,
                                startDate, endDate, pageable, out, excelRequest.toExcelGeneratorBuilder());
                return ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION,
                                                "attachment; filename=unused_extension_report.xlsx")
                                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                                .body(body);
        }

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
        public ResponseEntity<StreamingResponseBody> exportExcelSubdivisionUsageReport(@Parameter(hidden = true) Pageable pageable,
                        @ParameterObject PageableRequest pageableRequest,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate,
                        @RequestParam(required = false) Long parentSubdivisionId,
                        @ParameterObject ExcelRequest excelRequest) {
                StreamingResponseBody body = out ->
                        subdivisionReportService.exportExcelSubdivisionUsageReport(startDate, endDate,
                                parentSubdivisionId, pageable, out, excelRequest.toExcelGeneratorBuilder());
                return ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION,
                                                "attachment; filename=subdivision_usage_report.xlsx")
                                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                                .body(body);
        }

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
                        @Parameter(hidden = true) Pageable pageable, @ParameterObject PageableRequest pageableRequest,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate,
                        @RequestParam(required = false) Long parentSubdivisionId,
                        @ParameterObject ExcelRequest excelRequest) {
                StreamingResponseBody body = out ->
                        subdivisionReportService.exportExcelSubdivisionUsageByTypeReport(startDate, endDate,
                                parentSubdivisionId, pageable, out, excelRequest.toExcelGeneratorBuilder());
                return ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION,
                                                "attachment; filename=subdivision_usage_by_type_report.xlsx")
                                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                                .body(body);
        }

        @GetMapping(value = "telephonyTypeUsage", produces = MediaType.APPLICATION_JSON_VALUE)
        public Slice<TelephonyTypeUsageGroupDto> getTelephonyTypeUsageReport(
                        @Parameter(hidden = true) Pageable pageable, @ParameterObject PageableRequest pageableRequest,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate) {
                return telephonyUsageReportService.generateTelephonyTypeUsageReport(startDate, endDate, pageable);
        }

        @GetMapping(value = "telephonyTypeUsage/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
        public ResponseEntity<StreamingResponseBody> exportExcelTelephonyTypeUsageReport(@Parameter(hidden = true) Pageable pageable,
                        @ParameterObject PageableRequest pageableRequest,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate,
                        @ParameterObject ExcelRequest excelRequest) {
                StreamingResponseBody body = out ->
                        telephonyUsageReportService.exportExcelTelephonyTypeUsageReport(startDate, endDate,
                                pageable, out, excelRequest.toExcelGeneratorBuilder());
                return ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION,
                                                "attachment; filename=telephony_type_usage_report.xlsx")
                                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                                .body(body);
        }

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
                        @Parameter(hidden = true) Pageable pageable, @ParameterObject PageableRequest pageableRequest,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate,
                        @ParameterObject ExcelRequest excelRequest) {
                StreamingResponseBody body = out ->
                        telephonyUsageReportService.exportExcelMonthlyTelephonyTypeUsageReport(startDate, endDate,
                                pageable, out, excelRequest.toExcelGeneratorBuilder());
                return ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION,
                                                "attachment; filename=monthly_telephony_type_usage_report.xlsx")
                                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                                .body(body);
        }

        @GetMapping(value = "costCenterUsage", produces = MediaType.APPLICATION_JSON_VALUE)
        public SliceWithSummaries<CostCenterUsageReportDto, UsageReportSummaryDto> getCostCenterUsageReport(
                        @Parameter(hidden = true) Pageable pageable,
                        @ParameterObject PageableRequest pageableRequest,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate,
                        @RequestParam(required = false) Long parentCostCenterId) {
                return telephonyUsageReportService.generateCostCenterUsageReport(startDate, endDate, parentCostCenterId,
                                pageable);
        }

        @GetMapping(value = "costCenterUsage/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
        public ResponseEntity<StreamingResponseBody> exportExcelCostCenterUsageReport(@Parameter(hidden = true) Pageable pageable,
                        @ParameterObject PageableRequest pageableRequest,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate,
                        @RequestParam(required = false) Long parentCostCenterId,
                        @ParameterObject ExcelRequest excelRequest) {
                StreamingResponseBody body = out ->
                        telephonyUsageReportService.exportExcelCostCenterUsageReport(startDate, endDate,
                                parentCostCenterId, pageable, out, excelRequest.toExcelGeneratorBuilder());
                return ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION,
                                                "attachment; filename=cost_center_usage_report.xlsx")
                                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                                .body(body);
        }

        @GetMapping(value = "employeeAuthCodeUsage", produces = MediaType.APPLICATION_JSON_VALUE)
        public Slice<EmployeeAuthCodeUsageReportDto> getEmployeeAuthCodeUsageReport(
                        @Parameter(hidden = true) Pageable pageable, @ParameterObject PageableRequest pageableRequest,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate) {
                return employeeReportService.generateEmployeeAuthCodeUsageReport(startDate, endDate, pageable);
        }

        @GetMapping(value = "employeeAuthCodeUsage/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
        public ResponseEntity<StreamingResponseBody> exportExcelEmployeeAuthCodeUsageReport(
                        @Parameter(hidden = true) Pageable pageable, @ParameterObject PageableRequest pageableRequest,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate,
                        @ParameterObject ExcelRequest excelRequest) {
                StreamingResponseBody body = out ->
                        employeeReportService.exportExcelEmployeeAuthCodeUsageReport(startDate, endDate,
                                pageable, out, excelRequest.toExcelGeneratorBuilder());
                return ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION,
                                                "attachment; filename=employee_auth_code_usage_report.xlsx")
                                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                                .body(body);
        }

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
                        @Parameter(hidden = true) Pageable pageable, @ParameterObject PageableRequest pageableRequest,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate,
                        @RequestParam(required = false) List<Long> subdivisionIds,
                        @ParameterObject ExcelRequest excelRequest) {
                StreamingResponseBody body = out ->
                        subdivisionReportService.exportExcelMonthlySubdivisionUsageReport(startDate, endDate,
                                subdivisionIds, pageable, out, excelRequest.toExcelGeneratorBuilder());
                return ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION,
                                                "attachment; filename=monthly_subdivision_usage_report.xlsx")
                                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                                .body(body);
        }

        @GetMapping(value = "dialedNumberUsage", produces = MediaType.APPLICATION_JSON_VALUE)
        public Slice<DialedNumberUsageReportDto> getDialedNumberUsageReport(@Parameter(hidden = true) Pageable pageable,
                        @ParameterObject PageableRequest pageableRequest,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate) {
                return telephonyUsageReportService.generateDialedNumberUsageReport(startDate, endDate, pageable);
        }

        @GetMapping(value = "dialedNumberUsage/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
        public ResponseEntity<StreamingResponseBody> exportExcelDialedNumberUsageReport(@Parameter(hidden = true) Pageable pageable,
                        @ParameterObject PageableRequest pageableRequest,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate,
                        @ParameterObject ExcelRequest excelRequest) {
                StreamingResponseBody body = out ->
                        telephonyUsageReportService.exportExcelDialedNumberUsageReport(startDate, endDate,
                                pageable, out, excelRequest.toExcelGeneratorBuilder());
                return ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION,
                                                "attachment; filename=dialed_number_usage_report.xlsx")
                                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                                .body(body);
        }

        @GetMapping(value = "destinationUsage", produces = MediaType.APPLICATION_JSON_VALUE)
        public Slice<DestinationUsageReportDto> getDestinationUsageReport(@Parameter(hidden = true) Pageable pageable,
                        @ParameterObject PageableRequest pageableRequest,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate) {
                return telephonyUsageReportService.generateDestinationUsageReport(startDate, endDate, pageable);
        }

        @GetMapping(value = "destinationUsage/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
        public ResponseEntity<StreamingResponseBody> exportExcelDestinationUsageReport(@Parameter(hidden = true) Pageable pageable,
                        @ParameterObject PageableRequest pageableRequest,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate,
                        @ParameterObject ExcelRequest excelRequest) {
                StreamingResponseBody body = out ->
                        telephonyUsageReportService.exportExcelDestinationUsageReport(startDate, endDate,
                                pageable, out, excelRequest.toExcelGeneratorBuilder());
                return ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION,
                                                "attachment; filename=destination_usage_report.xlsx")
                                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                                .body(body);
        }

        @GetMapping(value = "highestConsumptionEmployee", produces = MediaType.APPLICATION_JSON_VALUE)
        public Slice<HighestConsumptionEmployeeReportDto> getHighestConsumptionEmployeeReport(
                        @Parameter(hidden = true) Pageable pageable, @ParameterObject PageableRequest pageableRequest,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate) {
                return employeeReportService.generateHighestConsumptionEmployeeReport(startDate, endDate, pageable);
        }

        @GetMapping(value = "highestConsumptionEmployee/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
        public ResponseEntity<StreamingResponseBody> exportExcelHighestConsumptionEmployeeReport(
                        @Parameter(hidden = true) Pageable pageable, @ParameterObject PageableRequest pageableRequest,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate,
                        @ParameterObject ExcelRequest excelRequest) {
                StreamingResponseBody body = out ->
                        employeeReportService.exportExcelHighestConsumptionEmployeeReport(startDate, endDate,
                                pageable, out, excelRequest.toExcelGeneratorBuilder());
                return ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION,
                                                "attachment; filename=highest_consumption_employee_report.xlsx")
                                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                                .body(body);
        }

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
        public ResponseEntity<StreamingResponseBody> exportExcelConferenceCallReport(@Parameter(hidden = true) Pageable pageable,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate,
                        @RequestParam(required = false) String extension,
                        @RequestParam(required = false) String employeeName,
                        @ParameterObject PageableRequest pageableRequest,
                        @ParameterObject ExcelRequest excelRequest) {
                StreamingResponseBody body = out ->
                        conferenceReportService.exportExcelConferenceCallsReport(startDate, endDate,
                                extension, employeeName, pageable, out, excelRequest.toExcelGeneratorBuilder());
                return ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=conference_calls.xlsx")
                                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                                .body(body);
        }

        @GetMapping(value = "extensionGroup", produces = MediaType.APPLICATION_JSON_VALUE)
        public SliceWithSummaries<ExtensionGroupDto, ExtensionGroupSummaryDto> getExtensionGroupReport(
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
