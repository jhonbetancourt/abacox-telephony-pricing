package com.infomedia.abacox.telephonypricing.entity;

import com.infomedia.abacox.telephonypricing.entity.superclass.ActivableEntity;
import jakarta.persistence.*;
import lombok.*;

import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnDefault;

/**
 * Entity representing subdivisions or departments.
 * Original table name: SUBDIRECCION
 */
@Entity
@Table(name = "subdivision")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class Subdivision extends ActivableEntity {

    /**
     * Primary key for the department.
     * Original field: SUBDIRECCION_ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "subdivision_id_seq")
    @SequenceGenerator(
            name = "subdivision_id_seq",
            sequenceName = "subdivision_id_seq",
            allocationSize = 1,
            initialValue = 10000000
    )
    @Column(name = "id", nullable = false)
    private Long id;

    /**
     * ID of the parent department.
     * Original field: SUBDIRECCION_PERTENECE
     */
    @Column(name = "parent_subdivision_id")
    private Long parentSubdivisionId;

    /**
     * Parent subdivision relationship.
     */
    @ManyToOne
    @JoinColumn(
            name = "parent_subdivision_id", 
            insertable = false, 
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_subdivision_parent")
    )
    private Subdivision parentSubdivision;

    /**
     * Name of the department.
     * Original field: SUBDIRECCION_NOMBRE
     */
    @Column(name = "name", length = 200, nullable = false)
    @ColumnDefault("")
    private String name;

    // The following field was commented out in the original schema:

    /**
     * ID of the department head (commented out in original schema).
     * Original field: SUBDIRECCION_JEFE
     */
    // @Column(name = "head_id", nullable = false)
    // @ColumnDefault("0")
    // private Integer headId;
}