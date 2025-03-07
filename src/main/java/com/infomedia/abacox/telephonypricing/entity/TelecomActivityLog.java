package com.infomedia.abacox.telephonypricing.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Entity representing a comprehensive telecom activity log that combines data from multiple tables.
 * Contains call/communication details, origin/destination information, and associated metadata.
 */
@Entity
@Table(name = "telecom_activity_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class TelecomActivityLog {

    /**
     * Primary key for the activity log.
     * Original field: ACUMTOTAL_ID
     */
    @Id
    @Column(name = "call_record_id")
    private Long callRecordId;

    /**
     * ID of the telecom type.
     * Original field: ACUMTOTAL_TIPOTELE_ID
     */
    @Column(name = "telecom_type_id")
    private Long telecomTypeId;
    
    /**
     * Telecom type name.
     * Original field: TIPOTELE_NOMBRE
     */
    @Column(name = "telecom_type_name")
    private String telecomTypeName;

    /**
     * ID of the originating employee.
     * Original field: ACUMTOTAL_FUNCIONARIO_ID
     */
    @Column(name = "source_employee_id")
    private Long sourceEmployeeId;

    /**
     * Name of the originating employee.
     * Original field: FUNCIONARIO_NOMBRE
     */
    @Column(name = "source_employee_name")
    private String sourceEmployeeName;

    /**
     * ID of the destination employee.
     * Original field: ACUMTOTAL_FUNDESTINO_ID
     */
    @Column(name = "destination_employee_id")
    private Long destinationEmployeeId;

    /**
     * Name of the destination employee.
     * Original field: FUNCIONARIO_NOMBRE_DESTINO
     */
    @Column(name = "destination_employee_name")
    private String destinationEmployeeName;

    /**
     * Extension of the originating employee.
     * Original field: ACUMTOTAL_FUN_EXTENSION
     */
    @Column(name = "source_extension")
    private String sourceExtension;

    /**
     * Dialed digits.
     * Original field: ACUMTOTAL_DIAL
     */
    @Column(name = "dialed_digits")
    private String dialedDigits;

    /**
     * Destination phone number.
     * Original field: ACUMTOTAL_TELEFONO_DESTINO
     */
    @Column(name = "destination_phone")
    private String destinationPhone;

    /**
     * ID of the operator.
     * Original field: ACUMTOTAL_OPERADOR_ID
     */
    @Column(name = "operator_id")
    private Long operatorId;
    
    /**
     * Operator name.
     * Original field: OPERADOR_NOMBRE
     */
    @Column(name = "operator_name")
    private String operatorName;

    /**
     * Date and time of service.
     * Original field: ACUMTOTAL_FECHA_SERVICIO
     */
    @Column(name = "service_timestamp")
    private LocalDateTime serviceTimestamp;

    /**
     * Inbound/Outbound flag.
     * Original field: ACUMTOTAL_IO
     */
    @Column(name = "is_inbound")
    private Boolean isInbound;

    /**
     * ID of the indicator (area code).
     * Original field: ACUMTOTAL_INDICATIVO_ID
     */
    @Column(name = "indicator_id")
    private Long indicatorId;

    /**
     * ID of the communication location.
     * Original field: ACUMTOTAL_COMUBICACION_ID
     */
    @Column(name = "communication_location_id")
    private Long communicationLocationId;
    
    /**
     * Communication location directory.
     * Original field: COMUBICACION_DIRECTORIO
     */
    @Column(name = "communication_location_directory")
    private String communicationLocationDirectory;

    /**
     * Trunk information.
     * Original field: ACUMTOTAL_TRONCAL
     */
    @Column(name = "trunk")
    private String trunk;

    /**
     * Initial trunk information.
     * Original field: ACUMTOTAL_TRONCALINI
     */
    @Column(name = "initial_trunk")
    private String initialTrunk;

    /**
     * Employee transfer information.
     * Original field: ACUMTOTAL_FUN_TRANSFER
     */
    @Column(name = "employee_transfer")
    private String employeeTransfer;

    /**
     * Assignment cause.
     * Original field: ACUMTOTAL_CAUSA_ASIGNA
     */
    @Column(name = "assignment_cause")
    private String assignmentCause;

    /**
     * Call duration in seconds.
     * Original field: ACUMTOTAL_TIEMPO
     */
    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    /**
     * Ring time.
     * Original field: ACUMTOTAL_REPIQUE
     */
    @Column(name = "ring_time")
    private Integer ringTime;

    /**
     * Billed amount.
     * Original field: ACUMTOTAL_VALOR_FACTURADO
     */
    @Column(name = "billed_amount", precision = 10, scale = 2)
    private BigDecimal billedAmount;

    /**
     * Price per minute.
     * Original field: ACUMTOTAL_PRECIOMINUTO
     */
    @Column(name = "price_per_minute", precision = 10, scale = 2)
    private BigDecimal pricePerMinute;

    /**
     * Transfer cause.
     * Original field: ACUMTOTAL_CAUSA_TRANSFER
     */
    @Column(name = "transfer_cause")
    private String transferCause;

    /**
     * Origin country.
     * Original field: ORIGEN_PAIS (aliased from INDICATIVO_DPTO_PAIS)
     */
    @Column(name = "origin_country")
    private String originCountry;

    /**
     * Origin city.
     * Original field: ORIGEN_CIUDAD (aliased from INDICATIVO_CIUDAD)
     */
    @Column(name = "origin_city")
    private String originCity;

    /**
     * Destination country.
     * Original field: DESTINO_PAIS (aliased from INDICATIVO_DPTO_PAIS)
     */
    @Column(name = "destination_country")
    private String destinationCountry;

    /**
     * Destination city.
     * Original field: DESTINO_CIUDAD (aliased from INDICATIVO_CIUDAD)
     */
    @Column(name = "destination_city")
    private String destinationCity;

    /**
     * Destination telecom type.
     * Original field: DESTINO_TIPOTELE (aliased from INDICATIVO_TIPOTELE_ID)
     */
    @Column(name = "destination_telecom_type")
    private Integer destinationTelecomType;

    /**
     * Origin municipality ID.
     * Original field: MPORIGEN_ID
     */
    @Column(name = "origin_country_id")
    private Long originCountryId;

    /**
     * Area/Department ID.
     * Original field: AREA_ID (aliased from FUNCIONARIO_SUBDIRECCION_ID)
     */
    @Column(name = "subdivision_id")
    private Long subdivisionId;

    /**
     * Cost center name.
     * Original field: CENTROCOSTOS_CENTRO_COSTO
     */
    @Column(name = "cost_center_name")
    private String costCenterName;

    /**
     * Work order number.
     * Original field: CENTROCOSTOS_OT
     */
    @Column(name = "work_order")
    private String workOrder;

    /**
     * Directory entry type.
     * Original field: DIRECTORIO_TIPO
     */
    @Column(name = "directory_type")
    private Boolean directoryType;

    /**
     * Directory contact name.
     * Original field: DIRECTORIO_NOMBRE
     */
    @Column(name = "directory_name")
    private String directoryName;

    /**
     * Directory employee ID.
     * Original field: DIRECTORIO_FUNCIONARIO_ID
     */
    @Column(name = "directory_employee_id")
    private Long directoryEmployeeId;

    /**
     * Company name.
     * Original field: EMPRESA_EMPRESA
     */
    @Column(name = "company_name")
    private String companyName;
}