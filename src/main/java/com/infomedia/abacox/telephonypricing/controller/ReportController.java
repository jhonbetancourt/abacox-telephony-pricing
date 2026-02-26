package com.infomedia.abacox.telephonypricing.controller;

import com.infomedia.abacox.telephonypricing.constants.DateTimePattern;
import com.infomedia.abacox.telephonypricing.db.entity.CallRecord;
import com.infomedia.abacox.telephonypricing.db.entity.FailedCallRecord;
import com.infomedia.abacox.telephonypricing.db.view.CorporateReportView;
import com.infomedia.abacox.telephonypricing.dto.callrecord.CallRecordDto;
import com.infomedia.abacox.telephonypricing.dto.failedcallrecord.FailedCallRecordDto;
import com.infomedia.abacox.telephonypricing.dto.generic.PageableRequest;
import com.infomedia.abacox.telephonypricing.dto.generic.PageWithSummaries;
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
        public Page<FailedCallRecordDto> getFailedCallRecords(@Parameter(hidden = true) Pageable pageable,
                        @Parameter(hidden = true) @Filter Specification<FailedCallRecord> spec,
                        @ParameterObject FilterRequest filterRequest) {
                return callRecordReportService.generateFailedCallRecordsReport(spec, pageable);
        }

        @GetMapping(value = "failedCallRecords/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
        public ResponseEntity<Resource> exportExcelFailedCallRecords(@Parameter(hidden = true) Pageable pageable,
                        @Parameter(hidden = true) @Filter Specification<FailedCallRecord> spec,
                        @ParameterObject FilterRequest filterRequest, @ParameterObject ExcelRequest excelRequest) {
                ByteArrayResource resource = callRecordReportService.exportExcelFailedCallRecordsReport(spec, pageable,
                                excelRequest.toExcelGeneratorBuilder());
                return ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION,
                                                "attachment; filename=failed_call_records.xlsx")
                                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                                .body(resource);
        }

        @GetMapping(value = "callRecords", produces = MediaType.APPLICATION_JSON_VALUE)
        public Page<CallRecordDto> getCallRecords(@Parameter(hidden = true) Pageable pageable,
                        @Parameter(hidden = true) @Filter Specification<CallRecord> spec,
                        @ParameterObject FilterRequest filterRequest) {
                return callRecordReportService.generateCallRecordsReport(spec, pageable);
        }

        @GetMapping(value = "callRecords/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
        public ResponseEntity<Resource> exportExcelCallRecords(@Parameter(hidden = true) Pageable pageable,
                        @Parameter(hidden = true) @Filter Specification<CallRecord> spec,
                        @ParameterObject FilterRequest filterRequest, @ParameterObject ExcelRequest excelRequest) {
                return ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=call_records.xlsx")
                                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                                .body(callRecordReportService.exportExcelCallRecordsReport(spec, pageable,
                                                excelRequest.toExcelGeneratorBuilder()));
        }

        @GetMapping(value = "corporateReport", produces = MediaType.APPLICATION_JSON_VALUE)
        public Page<CorporateReportDto> getCorporateReport(@Parameter(hidden = true) Pageable pageable,
                        @Parameter(hidden = true) @Filter Specification<CorporateReportView> spec,
                        @ParameterObject FilterRequest filterRequest) {
                return callRecordReportService.generateCorporateReport(spec, pageable);
        }

        @GetMapping(value = "corporateReport/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
        public ResponseEntity<Resource> exportExcelCorporateReport(@Parameter(hidden = true) Pageable pageable,
                        @Parameter(hidden = true) @Filter Specification<CorporateReportView> spec,
                        @ParameterObject FilterRequest filterRequest, @ParameterObject ExcelRequest excelRequest) {
                return ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=corporate_report.xlsx")
                                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                                .body(callRecordReportService.exportExcelCorporateReport(spec, pageable,
                                                excelRequest.toExcelGeneratorBuilder()));
        }

        @GetMapping(value = "employeeActivity", produces = MediaType.APPLICATION_JSON_VALUE)
        public Page<EmployeeActivityReportDto> getEmployeeActivityReport(@Parameter(hidden = true) Pageable pageable,
                        @ParameterObject PageableRequest pageableRequest,
                        @RequestParam(required = false) String employeeName,
                        @RequestParam(required = false) String employeeExtension,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate) {
                return employeeReportService.generateEmployeeActivityReport(employeeName, employeeExtension, startDate,
                                endDate,
                                pageable);
        }

        @GetMapping(value = "employeeActivity/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
        public ResponseEntity<Resource> exportExcelEmployeeActivityReport(@Parameter(hidden = true) Pageable pageable,
                        @RequestParam(required = false) String employeeName,
                        @RequestParam(required = false) String employeeExtension,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate,
                        @ParameterObject PageableRequest pageableRequest, @ParameterObject ExcelRequest excelRequest) {

                return ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION,
                                                "attachment; filename=employee_activity_report.xlsx")
                                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                                .body(employeeReportService.exportExcelEmployeeActivityReport(employeeName,
                                                employeeExtension, startDate, endDate, pageable,
                                                excelRequest.toExcelGeneratorBuilder()));
        }

        @GetMapping(value = "employeeCall", produces = MediaType.APPLICATION_JSON_VALUE)
        public Page<EmployeeCallReportDto> getEmployeeCallReport(@Parameter(hidden = true) Pageable pageable,
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
        public ResponseEntity<Resource> exportExcelEmployeeCallReport(@Parameter(hidden = true) Pageable pageable,
                        @RequestParam(required = false) String employeeName,
                        @RequestParam(required = false) String employeeExtension,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate,
                        @ParameterObject ExcelRequest excelRequest, @ParameterObject PageableRequest pageableRequest) {

                ByteArrayResource resource = employeeReportService.exportExcelEmployeeCallReport(employeeName,
                                employeeExtension, startDate, endDate, pageable,
                                excelRequest.toExcelGeneratorBuilder());
                return ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION,
                                                "attachment; filename=employee_call_report.xlsx")
                                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                                .body(resource);
        }

        @GetMapping(value = "unassignedCall", produces = MediaType.APPLICATION_JSON_VALUE)
        public Page<UnassignedCallReportDto> getUnassignedCallReport(@Parameter(hidden = true) Pageable pageable,
                        @ParameterObject PageableRequest pageableRequest,
                        @RequestParam(required = false) String extension,
                        @RequestParam(required = false, defaultValue = "EXTENSION") UnassignedCallGroupingType groupingType,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate) {
                return callRecordReportService.generateUnassignedCallReport(extension, groupingType, startDate, endDate,
                                pageable);
        }

        @GetMapping(value = "unassignedCall/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
        public ResponseEntity<Resource> exportExcelUnassignedCallReport(@Parameter(hidden = true) Pageable pageable,
                        @RequestParam(required = false) String extension,
                        @RequestParam(required = false, defaultValue = "EXTENSION") UnassignedCallGroupingType groupingType,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate,
                        @ParameterObject ExcelRequest excelRequest, @ParameterObject PageableRequest pageableRequest) {

                ByteArrayResource resource = callRecordReportService.exportExcelUnassignedCallReport(extension,
                                groupingType,
                                startDate,
                                endDate, pageable, excelRequest.toExcelGeneratorBuilder());
                return ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION,
                                                "attachment; filename=unassigned_call_report.xlsx")
                                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                                .body(resource);
        }

        @GetMapping(value = "processingFailure", produces = MediaType.APPLICATION_JSON_VALUE)
        public Page<ProcessingFailureReportDto> getProcessingFailureReport(@Parameter(hidden = true) Pageable pageable,
                        @ParameterObject PageableRequest pageableRequest,
                        @RequestParam(required = false) String directory,
                        @RequestParam(required = false) String errorType,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate) {
                return callRecordReportService.generateProcessingFailureReport(directory, errorType, startDate, endDate,
                                pageable);
        }

        @GetMapping(value = "processingFailure/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
        public ResponseEntity<Resource> exportExcelProcessingFailureReport(@Parameter(hidden = true) Pageable pageable,
                        @RequestParam(required = false) String directory,
                        @RequestParam(required = false) String errorType,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate,
                        @ParameterObject ExcelRequest excelRequest, @ParameterObject PageableRequest pageableRequest) {

                ByteArrayResource resource = callRecordReportService.exportExcelProcessingFailureReport(directory,
                                errorType,
                                startDate, endDate, pageable, excelRequest.toExcelGeneratorBuilder());
                return ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION,
                                                "attachment; filename=processing_failure_report.xlsx")
                                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                                .body(resource);
        }

        @GetMapping(value = "missedCallEmployee", produces = MediaType.APPLICATION_JSON_VALUE)
        public Page<MissedCallEmployeeReportDto> getMissedCallEmployeeReport(
                        @Parameter(hidden = true) Pageable pageable, @ParameterObject PageableRequest pageableRequest,
                        @RequestParam(required = false) String employeeName,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate) {
                return employeeReportService.generateMissedCallEmployeeReport(employeeName, startDate, endDate,
                                pageable);
        }

        @GetMapping(value = "missedCallEmployee/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
        public ResponseEntity<Resource> exportExcelMissedCallEmployeeReport(@Parameter(hidden = true) Pageable pageable,
                        @RequestParam(required = false) String employeeName,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate,
                        @ParameterObject ExcelRequest excelRequest,
                        @ParameterObject PageableRequest pageableRequest) {

                ByteArrayResource resource = employeeReportService.exportExcelMissedCallEmployeeReport(employeeName,
                                startDate,
                                endDate,
                                pageable, excelRequest.toExcelGeneratorBuilder());
                return ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION,
                                                "attachment; filename=missed_call_employee_report.xlsx")
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
                return extensionReportService.generateUnusedExtensionReport(employeeName, extension, startDate, endDate,
                                pageable);
        }

        @GetMapping(value = "unusedExtension/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
        public ResponseEntity<Resource> exportExcelUnusedExtensionReport(@Parameter(hidden = true) Pageable pageable,
                        @RequestParam(required = false) String employeeName,
                        @RequestParam(required = false) String extension,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate,
                        @ParameterObject ExcelRequest excelRequest,
                        @ParameterObject PageableRequest pageableRequest) {

                ByteArrayResource resource = extensionReportService.exportExcelUnusedExtensionReport(employeeName,
                                extension,
                                startDate,
                                endDate, pageable, excelRequest.toExcelGeneratorBuilder());
                return ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION,
                                                "attachment; filename=unused_extension_report.xlsx")
                                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                                .body(resource);
        }

        @GetMapping(value = "subdivisionUsage", produces = MediaType.APPLICATION_JSON_VALUE)
        public Page<SubdivisionUsageReportDto> getSubdivisionUsageReport(@Parameter(hidden = true) Pageable pageable,
                        @ParameterObject PageableRequest pageableRequest,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate,
                        @RequestParam(required = false) Long parentSubdivisionId) {
                return subdivisionReportService.generateSubdivisionUsageReport(startDate, endDate, parentSubdivisionId,
                                pageable);
        }

        @GetMapping(value = "subdivisionUsage/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
        public ResponseEntity<Resource> exportExcelSubdivisionUsageReport(@Parameter(hidden = true) Pageable pageable,
                        @ParameterObject PageableRequest pageableRequest,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate,
                        @RequestParam(required = false) Long parentSubdivisionId,
                        @ParameterObject ExcelRequest excelRequest) {

                ByteArrayResource resource = subdivisionReportService.exportExcelSubdivisionUsageReport(startDate,
                                endDate,
                                parentSubdivisionId, pageable, excelRequest.toExcelGeneratorBuilder());
                return ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION,
                                                "attachment; filename=subdivision_usage_report.xlsx")
                                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                                .body(resource);
        }

        @GetMapping(value = "subdivisionUsageByType", produces = MediaType.APPLICATION_JSON_VALUE)
        public Page<SubdivisionUsageByTypeReportDto> getSubdivisionUsageByTypeReport(
                        @Parameter(hidden = true) Pageable pageable, @ParameterObject PageableRequest pageableRequest,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate,
                        @RequestParam List<Long> subdivisionIds) {
                return subdivisionReportService.generateSubdivisionUsageByTypeReport(startDate, endDate, subdivisionIds,
                                pageable);
        }

        @GetMapping(value = "subdivisionUsageByType/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
        public ResponseEntity<Resource> exportExcelSubdivisionUsageByTypeReport(
                        @Parameter(hidden = true) Pageable pageable, @ParameterObject PageableRequest pageableRequest,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate,
                        @RequestParam List<Long> subdivisionIds, @ParameterObject ExcelRequest excelRequest) {

                ByteArrayResource resource = subdivisionReportService.exportExcelSubdivisionUsageByTypeReport(startDate,
                                endDate,
                                subdivisionIds, pageable, excelRequest.toExcelGeneratorBuilder());
                return ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION,
                                                "attachment; filename=subdivision_usage_by_type_report.xlsx")
                                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                                .body(resource);
        }

        @GetMapping(value = "telephonyTypeUsage", produces = MediaType.APPLICATION_JSON_VALUE)
        public Page<TelephonyTypeUsageGroupDto> getTelephonyTypeUsageReport(
                        @Parameter(hidden = true) Pageable pageable, @ParameterObject PageableRequest pageableRequest,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate) {
                return telephonyUsageReportService.generateTelephonyTypeUsageReport(startDate, endDate, pageable);
        }

        @GetMapping(value = "telephonyTypeUsage/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
        public ResponseEntity<Resource> exportExcelTelephonyTypeUsageReport(@Parameter(hidden = true) Pageable pageable,
                        @ParameterObject PageableRequest pageableRequest,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate,
                        @ParameterObject ExcelRequest excelRequest) {

                ByteArrayResource resource = telephonyUsageReportService.exportExcelTelephonyTypeUsageReport(startDate,
                                endDate,
                                pageable, excelRequest.toExcelGeneratorBuilder());
                return ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION,
                                                "attachment; filename=telephony_type_usage_report.xlsx")
                                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                                .body(resource);
        }

        @GetMapping(value = "monthlyTelephonyTypeUsage", produces = MediaType.APPLICATION_JSON_VALUE)
        public Page<MonthlyTelephonyTypeUsageGroupDto> getMonthlyTelephonyTypeUsageReport(
                        @Parameter(hidden = true) Pageable pageable, @ParameterObject PageableRequest pageableRequest,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate) {
                return telephonyUsageReportService.generateMonthlyTelephonyTypeUsageReport(startDate, endDate,
                                pageable);
        }

        @GetMapping(value = "monthlyTelephonyTypeUsage/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
        public ResponseEntity<Resource> exportExcelMonthlyTelephonyTypeUsageReport(
                        @Parameter(hidden = true) Pageable pageable, @ParameterObject PageableRequest pageableRequest,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate,
                        @ParameterObject ExcelRequest excelRequest) {

                ByteArrayResource resource = telephonyUsageReportService.exportExcelMonthlyTelephonyTypeUsageReport(
                                startDate,
                                endDate, pageable, excelRequest.toExcelGeneratorBuilder());
                return ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION,
                                                "attachment; filename=monthly_telephony_type_usage_report.xlsx")
                                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                                .body(resource);
        }

        @GetMapping(value = "costCenterUsage", produces = MediaType.APPLICATION_JSON_VALUE)
        public PageWithSummaries<CostCenterUsageReportDto, UsageReportSummaryDto> getCostCenterUsageReport(
                        @Parameter(hidden = true) Pageable pageable,
                        @ParameterObject PageableRequest pageableRequest,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate,
                        @RequestParam(required = false) Long parentCostCenterId) {
                return telephonyUsageReportService.generateCostCenterUsageReport(startDate, endDate, parentCostCenterId,
                                pageable);
        }

        @GetMapping(value = "costCenterUsage/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
        public ResponseEntity<Resource> exportExcelCostCenterUsageReport(@Parameter(hidden = true) Pageable pageable,
                        @ParameterObject PageableRequest pageableRequest,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate,
                        @RequestParam(required = false) Long parentCostCenterId,
                        @ParameterObject ExcelRequest excelRequest) {

                ByteArrayResource resource = telephonyUsageReportService.exportExcelCostCenterUsageReport(startDate,
                                endDate,
                                parentCostCenterId,
                                pageable, excelRequest.toExcelGeneratorBuilder());
                return ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION,
                                                "attachment; filename=cost_center_usage_report.xlsx")
                                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                                .body(resource);
        }

        @GetMapping(value = "employeeAuthCodeUsage", produces = MediaType.APPLICATION_JSON_VALUE)
        public Page<EmployeeAuthCodeUsageReportDto> getEmployeeAuthCodeUsageReport(
                        @Parameter(hidden = true) Pageable pageable, @ParameterObject PageableRequest pageableRequest,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate) {
                return employeeReportService.generateEmployeeAuthCodeUsageReport(startDate, endDate, pageable);
        }

        @GetMapping(value = "employeeAuthCodeUsage/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
        public ResponseEntity<Resource> exportExcelEmployeeAuthCodeUsageReport(
                        @Parameter(hidden = true) Pageable pageable, @ParameterObject PageableRequest pageableRequest,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate,
                        @ParameterObject ExcelRequest excelRequest) {

                ByteArrayResource resource = employeeReportService.exportExcelEmployeeAuthCodeUsageReport(startDate,
                                endDate,
                                pageable, excelRequest.toExcelGeneratorBuilder());
                return ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION,
                                                "attachment; filename=employee_auth_code_usage_report.xlsx")
                                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                                .body(resource);
        }

        @GetMapping(value = "monthlySubdivisionUsage", produces = MediaType.APPLICATION_JSON_VALUE)
        public Page<MonthlySubdivisionUsageReportDto> getMonthlySubdivisionUsageReport(
                        @Parameter(hidden = true) Pageable pageable, @ParameterObject PageableRequest pageableRequest,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate,
                        @RequestParam(required = false) List<Long> subdivisionIds) {
                return subdivisionReportService.generateMonthlySubdivisionUsageReport(startDate, endDate,
                                subdivisionIds,
                                pageable);
        }

        @GetMapping(value = "monthlySubdivisionUsage/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
        public ResponseEntity<Resource> exportExcelMonthlySubdivisionUsageReport(
                        @Parameter(hidden = true) Pageable pageable, @ParameterObject PageableRequest pageableRequest,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate,
                        @RequestParam(required = false) List<Long> subdivisionIds,
                        @ParameterObject ExcelRequest excelRequest) {

                ByteArrayResource resource = subdivisionReportService.exportExcelMonthlySubdivisionUsageReport(
                                startDate, endDate,
                                subdivisionIds, pageable, excelRequest.toExcelGeneratorBuilder());
                return ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION,
                                                "attachment; filename=monthly_subdivision_usage_report.xlsx")
                                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                                .body(resource);
        }

        @GetMapping(value = "dialedNumberUsage", produces = MediaType.APPLICATION_JSON_VALUE)
        public Page<DialedNumberUsageReportDto> getDialedNumberUsageReport(@Parameter(hidden = true) Pageable pageable,
                        @ParameterObject PageableRequest pageableRequest,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate) {
                return telephonyUsageReportService.generateDialedNumberUsageReport(startDate, endDate, pageable);
        }

        @GetMapping(value = "dialedNumberUsage/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
        public ResponseEntity<Resource> exportExcelDialedNumberUsageReport(@Parameter(hidden = true) Pageable pageable,
                        @ParameterObject PageableRequest pageableRequest,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate,
                        @ParameterObject ExcelRequest excelRequest) {

                ByteArrayResource resource = telephonyUsageReportService.exportExcelDialedNumberUsageReport(startDate,
                                endDate,
                                pageable, excelRequest.toExcelGeneratorBuilder());
                return ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION,
                                                "attachment; filename=dialed_number_usage_report.xlsx")
                                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                                .body(resource);
        }

        @GetMapping(value = "destinationUsage", produces = MediaType.APPLICATION_JSON_VALUE)
        public Page<DestinationUsageReportDto> getDestinationUsageReport(@Parameter(hidden = true) Pageable pageable,
                        @ParameterObject PageableRequest pageableRequest,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate) {
                return telephonyUsageReportService.generateDestinationUsageReport(startDate, endDate, pageable);
        }

        @GetMapping(value = "destinationUsage/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
        public ResponseEntity<Resource> exportExcelDestinationUsageReport(@Parameter(hidden = true) Pageable pageable,
                        @ParameterObject PageableRequest pageableRequest,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate,
                        @ParameterObject ExcelRequest excelRequest) {

                ByteArrayResource resource = telephonyUsageReportService.exportExcelDestinationUsageReport(startDate,
                                endDate,
                                pageable, excelRequest.toExcelGeneratorBuilder());
                return ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION,
                                                "attachment; filename=destination_usage_report.xlsx")
                                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                                .body(resource);
        }

        @GetMapping(value = "highestConsumptionEmployee", produces = MediaType.APPLICATION_JSON_VALUE)
        public Page<HighestConsumptionEmployeeReportDto> getHighestConsumptionEmployeeReport(
                        @Parameter(hidden = true) Pageable pageable, @ParameterObject PageableRequest pageableRequest,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate) {
                return employeeReportService.generateHighestConsumptionEmployeeReport(startDate, endDate, pageable);
        }

        @GetMapping(value = "highestConsumptionEmployee/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
        public ResponseEntity<Resource> exportExcelHighestConsumptionEmployeeReport(
                        @Parameter(hidden = true) Pageable pageable, @ParameterObject PageableRequest pageableRequest,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate,
                        @ParameterObject ExcelRequest excelRequest) {

                ByteArrayResource resource = employeeReportService.exportExcelHighestConsumptionEmployeeReport(
                                startDate,
                                endDate, pageable, excelRequest.toExcelGeneratorBuilder());
                return ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION,
                                                "attachment; filename=highest_consumption_employee_report.xlsx")
                                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                                .body(resource);
        }

        @GetMapping(value = "conferenceCalls", produces = MediaType.APPLICATION_JSON_VALUE)
        public Page<ConferenceGroupDto> getConferenceCallReport(@Parameter(hidden = true) Pageable pageable,
                        @ParameterObject PageableRequest pageableRequest,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate,
                        @RequestParam(required = false) String extension,
                        @RequestParam(required = false) String employeeName) {
                return conferenceReportService.generateConferenceCallsReport(startDate, endDate, extension,
                                employeeName, pageable);
        }

        @GetMapping(value = "conferenceCalls/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
        public ResponseEntity<Resource> exportExcelConferenceCallReport(@Parameter(hidden = true) Pageable pageable,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
                        @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate,
                        @RequestParam(required = false) String extension,
                        @RequestParam(required = false) String employeeName,
                        @ParameterObject PageableRequest pageableRequest,
                        @ParameterObject ExcelRequest excelRequest) {
                ByteArrayResource resource = conferenceReportService.exportExcelConferenceCallsReport(startDate,
                                endDate,
                                extension, employeeName,
                                pageable, excelRequest.toExcelGeneratorBuilder());
                return ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=conference_calls.xlsx")
                                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                                .body(resource);
        }

        @GetMapping(value = "extensionGroup", produces = MediaType.APPLICATION_JSON_VALUE)
        public PageWithSummaries<ExtensionGroupDto, ExtensionGroupSummaryDto> getExtensionGroupReport(
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
