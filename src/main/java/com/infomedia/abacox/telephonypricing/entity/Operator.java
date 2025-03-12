package com.infomedia.abacox.telephonypricing.entity;

import com.infomedia.abacox.telephonypricing.entity.superclass.AuditedEntity;
import jakarta.persistence.*;
import lombok.*;

import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.NotFound;
import org.hibernate.annotations.NotFoundAction;

/**
 * Entity representing telecom operators.
 * Original table name: OPERADOR
 */
@Entity
@Table(name = "operator")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class Operator extends AuditedEntity {

    /**
     * Primary key for the operator.
     * Original field: OPERADOR_ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "operator_id_seq")
    @SequenceGenerator(
            name = "operator_id_seq",
            sequenceName = "operator_id_seq",
            allocationSize = 1,
            initialValue = 1000000
    )
    @Column(name = "id", nullable = false)
    private Long id;

    /**
     * Name of the operator.
     * Original field: OPERADOR_NOMBRE
     */
    @Column(name = "name", length = 50, nullable = false)
    @ColumnDefault("")
    private String name;

    /**
     * ID of the origin municipality.
     * Original field: OPERADOR_MPORIGEN_ID
     */
    @Column(name = "origin_country_id", nullable = false)
    @ColumnDefault("1")
    private Long originCountryId;

    /**
     * Origin country relationship.
     */
    @ManyToOne
    @NotFound(action = NotFoundAction.IGNORE)
    @JoinColumn(name = "origin_country_id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(value = ConstraintMode.NO_CONSTRAINT))
    private OriginCountry originCountry;
}