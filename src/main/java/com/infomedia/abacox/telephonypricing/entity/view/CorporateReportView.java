package com.infomedia.abacox.telephonypricing.entity.view;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A read-only JPA entity that maps directly to the 'v_corporate_report' database view.
 * This entity is treated as immutable by Hibernate, which provides performance optimizations
 * as Hibernate will not perform dirty checking on these objects.
 */
@Entity
@Immutable // Key annotation for read-only entities/views
@Table(name = "v_corporate_report") // Maps this entity to the database view
@Getter
@NoArgsConstructor // Required by JPA
public class CorporateReportView {

    /**
     * The primary key of the view. This MUST map to a unique column in the view.
     * In our case, it's the primary key of the underlying 'call_record' table.
     */
    @Id
    @Column(name = "call_record_id")
    private Long callRecordId;

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

    @Column(name = "employee_extension")
    private String employeeExtension;

    @Column(name = "dial")
    private String dial;

    @Column(name = "destination_phone")
    private String destinationPhone;

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

    @Column(name = "origin_country")
    private String originCountry;

    @Column(name = "origin_city")
    private String originCity;

    @Column(name = "destination_country")
    private String destinationCountry;

    @Column(name = "destination_city")
    private String destinationCity;

    @Column(name = "origin_country_id")
    private Long originCountryId;

    @Column(name = "subdivision_id")
    private Long subdivisionId;

    @Column(name = "cost_center_name")
    private String costCenterName;

    @Column(name = "work_order")
    private String workOrder;

    @Column(name = "contact_type")
    private Boolean contactType;

    @Column(name = "contact_name")
    private String contactName;

    @Column(name = "contact_employee_id")
    private Long contactEmployeeId;

    @Column(name = "company_name")
    private String companyName;
}