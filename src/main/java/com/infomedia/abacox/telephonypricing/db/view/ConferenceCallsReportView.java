package com.infomedia.abacox.telephonypricing.db.view;

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
 * A read-only view entity representing a detailed log of conference calls.
 * A conference call is identified by a transfer_cause of 10 or 3.
 */
@Entity
@Immutable
@Table(name = "v_conference_calls_report")
@Getter
@NoArgsConstructor
public class ConferenceCallsReportView {

    @Id
    @Column(name = "call_record_id")
    private Long callRecordId;

    @Column(name = "service_date")
    private LocalDateTime serviceDate;

    @Column(name = "employee_extension")
    private String employeeExtension;

    @Column(name = "duration")
    private Integer duration;

    @Column(name = "is_incoming")
    private Boolean isIncoming;

    @Column(name = "dialed_number")
    private String dialedNumber;

    @Column(name = "billed_amount")
    private BigDecimal billedAmount;

    @Column(name = "employee_id")
    private Long employeeId;

    @Column(name = "employee_auth_code")
    private String employeeAuthCode;

    @Column(name = "operator_id")
    private Long operatorId;

    @Column(name = "operator_name")
    private String operatorName;

    @Column(name = "telephony_type_name")
    private String telephonyTypeName;

    @Column(name = "telephony_type_id")
    private Long telephonyTypeId;

    @Column(name = "company_name")
    private String companyName;

    @Column(name = "contact_type")
    private Boolean contactType;

    @Column(name = "contact_name")
    private String contactName;

    @Column(name = "contact_owner_id")
    private Long contactOwnerId;

    @Column(name = "transfer_cause")
    private Integer transferCause;

    @Column(name = "transfer_key")
    private String transferKey;
}