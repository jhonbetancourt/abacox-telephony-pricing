package com.infomedia.abacox.telephonypricing.entity;

import com.infomedia.abacox.telephonypricing.entity.superclass.ActivableEntity;
import jakarta.persistence.*;
import lombok.*;

import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.NotFound;
import org.hibernate.annotations.NotFoundAction;

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
            initialValue = 1000000
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
    @Column(name = "subdivision_id", nullable = false)
    @ColumnDefault("0")
    private Long subdivisionId;

    /**
     * Subdivision relationship.
     */
    @ManyToOne
    @NotFound(action = NotFoundAction.IGNORE)
    @JoinColumn(name = "subdivision_id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(value = ConstraintMode.NO_CONSTRAINT))
    private Subdivision subdivision;

    /**
     * ID of the cost center.
     * Original field: FUNCIONARIO_CENTROCOSTOS_ID
     */
    @Column(name = "cost_center_id", nullable = false)
    @ColumnDefault("0")
    private Long costCenterId;

    /**
     * Cost center relationship.
     */
    @ManyToOne
    @NotFound(action = NotFoundAction.IGNORE)
    @JoinColumn(name = "cost_center_id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(value = ConstraintMode.NO_CONSTRAINT))
    private CostCenter costCenter;

    /**
     * Access key or password.
     * Original field: FUNCIONARIO_CLAVE
     */
    @Column(name = "access_key", length = 50, nullable = false)
    @ColumnDefault("")
    private String accessKey;

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
    @Column(name = "communication_location_id", nullable = false)
    @ColumnDefault("0")
    private Long communicationLocationId;

    /**
     * Communication location relationship.
     */
    @ManyToOne
    @NotFound(action = NotFoundAction.IGNORE)
    @JoinColumn(name = "communication_location_id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(value = ConstraintMode.NO_CONSTRAINT))
    private CommunicationLocation communicationLocation;

    /**
     * ID of the employee's position/role.
     * Original field: FUNCIONARIO_FUNCARGO_ID
     */
    @Column(name = "job_position_id", nullable = false)
    @ColumnDefault("0")
    private Long jobPositionId;

    /**
     * Job position relationship.
     */
    @ManyToOne
    @NotFound(action = NotFoundAction.IGNORE)
    @JoinColumn(name = "job_position_id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(value = ConstraintMode.NO_CONSTRAINT))
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
    @Column(name = "telephone", length = 100, nullable = false)
    @ColumnDefault("")
    private String telephone;

    /**
     * Address.
     * Original field: FUNCIONARIO_DIRECCION
     */
    @Column(name = "address", length = 255, nullable = false)
    @ColumnDefault("")
    private String address;

    /**
     * Cell phone number.
     * Original field: FUNCIONARIO_CELULAR
     */
    @Column(name = "cell_phone", length = 20, nullable = false)
    @ColumnDefault("")
    private String cellPhone;

    /**
     * ID number/document.
     * Original field: FUNCIONARIO_NUMEROID
     */
    @Column(name = "id_number", length = 20, nullable = false)
    @ColumnDefault("")
    private String idNumber;


    // The following fields were commented out in the original schema:
    // The comment indicates they're added automatically

    /**
     * Start date of history record (commented out in original schema).
     * Original field: FUNCIONARIO_HISTODESDE
     * Note: Added automatically according to original schema comment.
     */
    // @Column(name = "history_start_date")
    // private LocalDateTime historyStartDate;

    /**
     * History of changes (commented out in original schema).
     * Original field: FUNCIONARIO_HISTOCAMBIO
     * Note: Added automatically according to original schema comment.
     */
    // @Column(name = "change_history", columnDefinition = "TEXT")
    // private String changeHistory;

    /**
     * ID of the history control (commented out in original schema).
     * Original field: FUNCIONARIO_HISTORICTL_ID
     * Note: Added automatically according to original schema comment.
     */
    // @Column(name = "history_control_id", nullable = false)
    // @ColumnDefault("0")
    // private Integer historyControlId;
}