package com.infomedia.abacox.telephonypricing.entity;

import com.infomedia.abacox.telephonypricing.entity.superclass.ActivableEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

/**
 * Entity representing subdivision managers.
 * Original table name: JEFESUBDIR
 */
@Entity
@Table(name = "subdivision_manager")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class SubdivisionManager extends ActivableEntity {

    /**
     * Primary key for the subdivision manager.
     * Original field: JEFESUBDIR_ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "subdivision_manager_id_seq")
    @SequenceGenerator(
            name = "subdivision_manager_id_seq",
            sequenceName = "subdivision_manager_id_seq",
            allocationSize = 1,
            initialValue = 10000000
    )
    @Column(name = "id", nullable = false)
    private Long id;

    /**
     * ID of the subdivision/department.
     * Original field: JEFESUBDIR_SUBDIRECCION_ID
     */
    @Column(name = "subdivision_id", nullable = false)
    private Long subdivisionId;

    /**
     * Subdivision relationship.
     */
    @ManyToOne
    @JoinColumn(
            name = "subdivision_id",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_subdivision_manager_subdivision")
    )
    private Subdivision subdivision;

    /**
     * ID of the manager employee.
     * Original field: JEFESUBDIR_JEFE
     */
    @Column(name = "manager_id", nullable = false)
    private Long managerId;

    /**
     * Manager employee relationship.
     */
    @ManyToOne
    @JoinColumn(
            name = "manager_id",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_subdivision_manager_employee")
    )
    private Employee manager;
}