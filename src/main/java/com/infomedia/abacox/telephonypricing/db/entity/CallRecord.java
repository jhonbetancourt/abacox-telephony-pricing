package com.infomedia.abacox.telephonypricing.db.entity;

import com.infomedia.abacox.telephonypricing.db.entity.superclass.AuditedEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnDefault;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(
    name = "call_record",
    indexes = {
        // 1. Primary Date Range Filtering (Used in ALL reports)
        @Index(name = "idx_call_record_service_date", columnList = "service_date"),

        // 2. Unassigned Call Report & Employee Lookup
        // Filters: LENGTH(employee_extension) BETWEEN 3 AND 5
        @Index(name = "idx_call_record_ext_svc", columnList = "employee_extension, service_date"),

        // 3. Missed Call Report (Complex UNION query)
        // Uses employee_transfer to link back to employee extension
        @Index(name = "idx_call_record_transfer", columnList = "employee_transfer"),
        // 4. Dialed Number Usage Report
        // Joins on cr.dial = contact.phone_number
        @Index(name = "idx_call_record_dial_svc", columnList = "dial, service_date"),

        // 5. Telephony Type & Subdivision Reports
        @Index(name = "idx_call_record_tt_svc", columnList = "telephony_type_id, service_date"),
        
        // 6. Foreign Key Optimization (Joins)
        @Index(name = "idx_call_record_employee", columnList = "employee_id"),
        @Index(name = "idx_call_record_comm_loc", columnList = "comm_location_id"),
        @Index(name = "idx_call_record_operator", columnList = "operator_id"),
        @Index(name = "idx_call_record_indicator", columnList = "indicator_id"),
        @Index(name = "idx_call_record_dest_emp", columnList = "destination_employee_id"),
        
        // 7. File reprocessing / Cleanup
        @Index(name = "idx_call_record_file_info", columnList = "file_info_id"),
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString
@SuperBuilder(toBuilder = true)
public class CallRecord extends AuditedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "call_record_id_seq")
    @SequenceGenerator(name = "call_record_id_seq", sequenceName = "call_record_id_seq", allocationSize = 1, initialValue = 1000000000)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "dial", length = 50, nullable = false)
    @ColumnDefault("''")
    private String dial;

    @Column(name = "comm_location_id")
    private Long commLocationId;
    
    @ManyToOne
    @JoinColumn(name = "comm_location_id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_call_record_comm_location"))
    private CommunicationLocation commLocation;

    @Column(name = "service_date", nullable = false)
    private LocalDateTime serviceDate;

    @Column(name = "operator_id")
    private Long operatorId;
    
    @ManyToOne
    @JoinColumn(name = "operator_id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_call_record_operator"))
    private Operator operator;

    @Column(name = "employee_extension", length = 50, nullable = false)
    @ColumnDefault("''")
    private String employeeExtension;

    @Column(name = "employee_auth_code", length = 50, nullable = false)
    @ColumnDefault("''")
    private String employeeAuthCode;

    @Column(name = "indicator_id")
    private Long indicatorId;
    
    @ManyToOne
    @JoinColumn(name = "indicator_id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_call_record_indicator"))
    private Indicator indicator;

    @Column(name = "destination_phone", length = 50, nullable = false)
    @ColumnDefault("''")
    private String destinationPhone;

    @Column(name = "duration", nullable = false)
    @ColumnDefault("0")
    private Integer duration;

    @Column(name = "ring_count", nullable = false)
    @ColumnDefault("0")
    private Integer ringCount;

    @Column(name = "telephony_type_id")
    private Long telephonyTypeId;
    
    @ManyToOne
    @JoinColumn(name = "telephony_type_id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_call_record_telephony_type"))
    private TelephonyType telephonyType;

    @Column(name = "billed_amount", nullable = false)
    @ColumnDefault("0")
    private BigDecimal billedAmount;

    @Column(name = "price_per_minute", nullable = false)
    @ColumnDefault("0")
    private BigDecimal pricePerMinute;

    @Column(name = "initial_price", nullable = false)
    @ColumnDefault("0")
    private BigDecimal initialPrice;

    @Column(name = "is_incoming", nullable = false)
    @ColumnDefault("false")
    private Boolean isIncoming;

    @Column(name = "trunk", length = 50, nullable = false)
    @ColumnDefault("''")
    private String trunk;

    @Column(name = "initial_trunk", length = 50, nullable = false)
    @ColumnDefault("''")
    private String initialTrunk;

    @Column(name = "employee_id")
    private Long employeeId;
    
    @ManyToOne
    @JoinColumn(name = "employee_id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_call_record_employee"))
    private Employee employee;

    @Column(name = "employee_transfer", length = 50)
    @ColumnDefault("''")
    private String employeeTransfer;

    @Column(name = "transfer_cause", nullable = false)
    @ColumnDefault("0")
    private Integer transferCause;

    @Column(name = "assignment_cause")
    private Integer assignmentCause;

    @Column(name = "destination_employee_id")
    private Long destinationEmployeeId;
    
    @ManyToOne
    @JoinColumn(name = "destination_employee_id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_call_record_destination_employee"))
    private Employee destinationEmployee;

    @Column(name = "file_info_id")
    private Long fileInfoId;
    
    @ManyToOne
    @JoinColumn(name = "file_info_id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_call_record_file_info"))
    private FileInfo fileInfo;

    @ToString.Exclude
    @Column(name = "ctl_hash")
    private Long ctlHash;

    @ToString.Exclude
    @Column(name = "cdr_string", columnDefinition = "BYTEA")
    private byte[] cdrString;
}