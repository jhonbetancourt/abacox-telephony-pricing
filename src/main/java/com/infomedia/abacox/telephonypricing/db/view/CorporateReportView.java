package com.infomedia.abacox.telephonypricing.db.view;

import com.infomedia.abacox.telephonypricing.db.util.ExcludeFromDdl;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Immutable
@Table(name = "v_corporate_report")
@Getter
@NoArgsConstructor
@ExcludeFromDdl
public class CorporateReportView {

    @Id
    @Column(name = "call_record_id")
    private Long callRecordId;

    @Column(name = "origin_phone")
    private String originPhone;

    @Column(name = "destination_phone")
    private String destinationPhone;

    @Column(name = "origin_location")
    private String originLocation;

    @Column(name = "destination_location")
    private String destinationLocation;

    @Column(name = "cost_center")
    private String costCenter;

    @Column(name = "telephony_type_id")
    private Long telephonyTypeId;

    @Column(name = "telephony_type_name")
    private String telephonyTypeName;

    @Column(name = "employee_id")
    private Long employeeId;

    @Column(name = "employee_name")
    private String employeeName;

    @Column(name = "destination_employee_id")
    private Long destinationEmployeeId;

    @Column(name = "destination_employee_name")
    private String destinationEmployeeName;

    @Column(name = "operator_id")
    private Long operatorId;

    @Column(name = "operator_name")
    private String operatorName;

    @Column(name = "service_date")
    private LocalDateTime serviceDate;

    @Column(name = "is_incoming")
    private Boolean isIncoming;

    @Column(name = "indicator_id")
    private Long indicatorId;

    @Column(name = "comm_location_id")
    private Long commLocationId;

    @Column(name = "comm_location_directory")
    private String commLocationDirectory;

    @Column(name = "trunk")
    private String trunk;

    @Column(name = "initial_trunk")
    private String initialTrunk;

    @Column(name = "employee_transfer")
    private String employeeTransfer;

    @Column(name = "assignment_cause")
    private Integer assignmentCause;

    @Column(name = "duration")
    private Integer duration;

    @Column(name = "ring_count")
    private Integer ringCount;

    @Column(name = "billed_amount")
    private BigDecimal billedAmount;

    @Column(name = "price_per_minute")
    private BigDecimal pricePerMinute;

    @Column(name = "transfer_cause")
    private Integer transferCause;

    @Column(name = "origin_country_id")
    private Long originCountryId;

    @Column(name = "origin_country_name")
    private String originCountryName;

    @Column(name = "subdivision_id")
    private Long subdivisionId;

    @Column(name = "subdivision_name")
    private String subdivisionName;

    @Column(name = "contact_type")
    private Boolean contactType;

    @Column(name = "contact_name")
    private String contactName;

    @Column(name = "contact_employee_id")
    private Long contactEmployeeId;

    @Column(name = "company_name")
    private String companyName;
}