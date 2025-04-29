package com.infomedia.abacox.telephonypricing.entity;

import com.infomedia.abacox.telephonypricing.entity.superclass.ActivableEntity;
import jakarta.persistence.*;
import lombok.*;

import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnDefault;

/**
 * Entity representing employees or staff members.
 * Original table name: FUNCIONARIO
 */
@Entity
@Table(name = "employee")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class Employee extends ActivableEntity {

    /**
     * Primary key for the employee.
     * Original field: FUNCIONARIO_ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "employee_id_seq")
    @SequenceGenerator(
            name = "employee_id_seq",
            sequenceName = "employee_id_seq",
            allocationSize = 1,
            initialValue = 10000000
    )
    @Column(name = "id", nullable = false)
    private Long id;

    /**
     * Name of the employee.
     * Original field: FUNCIONARIO_NOMBRE
     */
    @Column(name = "name", length = 255, nullable = false)
    @ColumnDefault("")
    private String name;

    /**
     * ID of the subdivision/department.
     * Original field: FUNCIONARIO_SUBDIRECCION_ID
     */
    @Column(name = "subdivision_id")
    private Long subdivisionId;

    /**
     * Subdivision relationship.
     */
    @ManyToOne
    @JoinColumn(
            name = "subdivision_id",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_employee_subdivision")
    )
    private Subdivision subdivision;

    /**
     * ID of the cost center.
     * Original field: FUNCIONARIO_CENTROCOSTOS_ID
     */
    @Column(name = "cost_center_id")
    private Long costCenterId;

    /**
     * Cost center relationship.
     */
    @ManyToOne
    @JoinColumn(
            name = "cost_center_id",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_employee_cost_center")
    )
    private CostCenter costCenter;

    /**
     * Represents an Authorization Code or Account Code entered by a user when making a call
     * Original field: FUNCIONARIO_CLAVE
     */
    @Column(name = "auth_code", length = 50, nullable = false)
    @ColumnDefault("")
    private String authCode;

    /**
     * Telephone extension.
     * Original field: FUNCIONARIO_EXTENSION
     */
    @Column(name = "extension", length = 50, nullable = false)
    @ColumnDefault("")
    private String extension;

    /**
     * ID of the communication location.
     * Original field: FUNCIONARIO_COMUBICACION_ID
     */
    @Column(name = "communication_location_id")
    private Long communicationLocationId;

    /**
     * Communication location relationship.
     */
    @ManyToOne
    @JoinColumn(
            name = "communication_location_id",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_employee_communication_location")
    )
    private CommunicationLocation communicationLocation;

    /**
     * ID of the employee's position/role.
     * Original field: FUNCIONARIO_FUNCARGO_ID
     */
    @Column(name = "job_position_id")
    private Long jobPositionId;

    /**
     * Job position relationship.
     */
    @ManyToOne
    @JoinColumn(
            name = "job_position_id",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_employee_job_position")
    )
    private JobPosition jobPosition;

    /**
     * Email address.
     * Original field: FUNCIONARIO_CORREO
     */
    @Column(name = "email", length = 100, nullable = false)
    @ColumnDefault("")
    private String email;

    /**
     * Telephone number.
     * Original field: FUNCIONARIO_TELEFONO
     */
    @Column(name = "phone", length = 50, nullable = false)
    @ColumnDefault("")
    private String phone;

    /**
     * Address.
     * Original field: FUNCIONARIO_DIRECCION
     */
    @Column(name = "address", length = 255, nullable = false)
    @ColumnDefault("")
    private String address;

    /**
     * ID number/document.
     * Original field: FUNCIONARIO_NUMEROID
     */
    @Column(name = "id_number", length = 20, nullable = false)
    @ColumnDefault("")
    private String idNumber;
}