package com.infomedia.abacox.telephonypricing.entity;

import com.infomedia.abacox.telephonypricing.entity.superclass.AuditedEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnDefault;

/**
 * Entity representing accumulated call records/totals.
 * Original table name: ACUMTOTAL
 */
@Entity
@Table(name = "call_record")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class CallRecord extends AuditedEntity {

    /**
     * Primary key for the call record.
     * Original field: ACUMTOTAL_ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "call_record_id_seq")
    @SequenceGenerator(
            name = "call_record_id_seq",
            sequenceName = "call_record_id_seq",
            allocationSize = 1,
            initialValue = 10000000
    )
    @Column(name = "id", nullable = false)
    private Long id;

    /**
     * Dialed number or sequence.
     * Original field: ACUMTOTAL_DIAL
     */
    @Column(name = "dial", length = 50, nullable = false)
    @ColumnDefault("")
    private String dial;

    /**
     * ID of the communication location.
     * Original field: ACUMTOTAL_COMUBICACION_ID
     */
    @Column(name = "comm_location_id", nullable = true)
    private Long commLocationId;
    
    /**
     * Communication location relationship.
     */
    @ManyToOne
    @JoinColumn(
        name = "comm_location_id", 
        insertable = false, 
        updatable = false,
        foreignKey = @ForeignKey(name = "fk_call_record_comm_location")
    )
    private CommunicationLocation commLocation;

    /**
     * Date and time of the service.
     * Original field: ACUMTOTAL_FECHA_SERVICIO
     */
    @Column(name = "service_date", nullable = false)
    private LocalDateTime serviceDate;

    /**
     * ID of the operator.
     * Original field: ACUMTOTAL_OPERADOR_ID
     */
    @Column(name = "operator_id", nullable = true)
    private Long operatorId;
    
    /**
     * Operator relationship.
     */
    @ManyToOne
    @JoinColumn(
        name = "operator_id", 
        insertable = false, 
        updatable = false,
        foreignKey = @ForeignKey(name = "fk_call_record_operator")
    )
    private Operator operator;

    /**
     * Extension of the employee/user.
     * Original field: ACUMTOTAL_FUN_EXTENSION
     */
    @Column(name = "employee_extension", length = 50, nullable = false)
    @ColumnDefault("")
    private String employeeExtension;

    /**
     * Key or code of the employee/user.
     * Original field: ACUMTOTAL_FUN_CLAVE
     */
    @Column(name = "employee_auth_code", length = 50, nullable = false)
    @ColumnDefault("")
    private String employeeAuthCode;

    /**
     * ID of the indicator/area code.
     * Original field: ACUMTOTAL_INDICATIVO_ID
     */
    @Column(name = "indicator_id", nullable = true)
    private Long indicatorId;
    
    /**
     * Indicator relationship.
     */
    @ManyToOne
    @JoinColumn(
        name = "indicator_id", 
        insertable = false, 
        updatable = false,
        foreignKey = @ForeignKey(name = "fk_call_record_indicator")
    )
    private Indicator indicator;

    /**
     * Destination telephone number.
     * Original field: ACUMTOTAL_TELEFONO_DESTINO
     */
    @Column(name = "destination_phone", length = 50, nullable = false)
    @ColumnDefault("")
    private String destinationPhone;

    /**
     * Duration of the call in seconds.
     * Original field: ACUMTOTAL_TIEMPO
     */
    @Column(name = "duration", nullable = false)
    @ColumnDefault("0")
    private Integer duration;

    /**
     * Ringing time or count.
     * Original field: ACUMTOTAL_REPIQUE
     */
    @Column(name = "ring_count", nullable = false)
    @ColumnDefault("0")
    private Integer ringCount;

    /**
     * ID of the telephone type.
     * Original field: ACUMTOTAL_TIPOTELE_ID
     */
    @Column(name = "telephony_type_id", nullable = true)
    private Long telephonyTypeId;
    
    /**
     * Telephony type relationship.
     */
    @ManyToOne
    @JoinColumn(
        name = "telephony_type_id", 
        insertable = false, 
        updatable = false,
        foreignKey = @ForeignKey(name = "fk_call_record_telephony_type")
    )
    private TelephonyType telephonyType;

    /**
     * Billed amount for the call.
     * Original field: ACUMTOTAL_VALOR_FACTURADO
     */
    @Column(name = "billed_amount", nullable = false)
    @ColumnDefault("0")
    private BigDecimal billedAmount;

    /**
     * Price per minute for the call.
     * Original field: ACUMTOTAL_PRECIOMINUTO
     */
    @Column(name = "price_per_minute", nullable = false)
    @ColumnDefault("0")
    private BigDecimal pricePerMinute;

    /**
     * Initial price for the call.
     * Original field: ACUMTOTAL_PRECIOINICIAL
     */
    @Column(name = "initial_price", nullable = false)
    @ColumnDefault("0")
    private BigDecimal initialPrice;

    /**
     * Flag for incoming/outgoing call.
     * Original field: ACUMTOTAL_IO
     */
    @Column(name = "is_incoming", nullable = false)
    @ColumnDefault("false")
    private boolean isIncoming;

    /**
     * Trunk line used for the call.
     * Original field: ACUMTOTAL_TRONCAL
     */
    @Column(name = "trunk", length = 50, nullable = false)
    @ColumnDefault("")
    private String trunk;

    /**
     * Initial trunk line for the call.
     * Original field: ACUMTOTAL_TRONCALINI
     */
    @Column(name = "initial_trunk", length = 50, nullable = false)
    @ColumnDefault("")
    private String initialTrunk;

    /**
     * ID of the employee/user.
     * Original field: ACUMTOTAL_FUNCIONARIO_ID
     */
    @Column(name = "employee_id", nullable = true)
    private Long employeeId;
    
    /**
     * Employee relationship.
     */
    @ManyToOne
    @JoinColumn(
        name = "employee_id", 
        insertable = false, 
        updatable = false,
        foreignKey = @ForeignKey(name = "fk_call_record_employee")
    )
    private Employee employee;

    /**
     * Transfer information for the employee.
     * Original field: ACUMTOTAL_FUN_TRANSFER
     */
    @Column(name = "employee_transfer", length = 50, nullable = false)
    @ColumnDefault("")
    private String employeeTransfer;

    /**
     * Cause of transfer code.
     * Original field: ACUMTOTAL_CAUSA_TRANSFER
     */
    @Column(name = "transfer_cause", nullable = false)
    @ColumnDefault("0")
    private Integer transferCause;

    /**
     * Assignment cause code.
     * Original field: ACUMTOTAL_CAUSA_ASIGNA
     */
    @Column(name = "assignment_cause", nullable = false)
    @ColumnDefault("0")
    private Integer assignmentCause;

    /**
     * ID of the destination employee.
     * Original field: ACUMTOTAL_FUNDESTINO_ID
     */
    @Column(name = "destination_employee_id", nullable = true)
    private Long destinationEmployeeId;
    
    /**
     * Destination employee relationship.
     */
    @ManyToOne
    @JoinColumn(
        name = "destination_employee_id", 
        insertable = false, 
        updatable = false,
        foreignKey = @ForeignKey(name = "fk_call_record_destination_employee")
    )
    private Employee destinationEmployee;

    /**
     * ID of the file information.
     * Original field: ACUMTOTAL_FILEINFO_ID
     */
    @Column(name = "file_info_id", nullable = true)
    private Long fileInfoId;
    
    /**
     * File information relationship.
     */
    @ManyToOne
    @JoinColumn(
        name = "file_info_id", 
        insertable = false, 
        updatable = false,
        foreignKey = @ForeignKey(name = "fk_call_record_file_info")
    )
    private FileInfo fileInfo;

    /**
     * ID of the centralized system.
     * Original field: ACUMTOTAL_CENTRALIZADA_ID
     * Note: This field was nullable in the original schema.
     */
    @Column(name = "centralized_id")
    private Long centralizedId;

    /**
     * IP address of origin.
     * Original field: ACUMTOTAL_IPORIGEN
     * Note: This field was nullable in the original schema.
     */
    @Column(name = "origin_ip", length = 64)
    private String originIp;
}