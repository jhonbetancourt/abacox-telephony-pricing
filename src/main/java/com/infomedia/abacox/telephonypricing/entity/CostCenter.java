package com.infomedia.abacox.telephonypricing.entity;

import com.infomedia.abacox.telephonypricing.entity.superclass.AuditedEntity;
import jakarta.persistence.*;
import lombok.*;

import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.NotFound;
import org.hibernate.annotations.NotFoundAction;

/**
 * Entity representing cost centers.
 * Original table name: CENTROCOSTOS
 */
@Entity
@Table(name = "cost_center")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class CostCenter extends AuditedEntity {

    /**
     * Primary key for the cost center.
     * Original field: CENTROCOSTOS_ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "cost_center_id_seq")
    @SequenceGenerator(
            name = "cost_center_id_seq",
            sequenceName = "cost_center_id_seq",
            allocationSize = 1,
            initialValue = 1000000
    )
    @Column(name = "id", nullable = false)
    private Long id;

    /**
     * Name or identifier of the cost center.
     * Original field: CENTROCOSTOS_CENTRO_COSTO
     */
    @Column(name = "cost_center_name", length = 100, nullable = false)
    @ColumnDefault("")
    private String costCenterName;

    /**
     * Work order code.
     * Original field: CENTROCOSTOS_OT
     */
    @Column(name = "work_order", length = 50, nullable = false)
    @ColumnDefault("")
    private String workOrder;

    /**
     * ID of the parent cost center.
     * Original field: CENTROCOSTOS_PERTENECE
     */
    @Column(name = "parent_cost_center_id", nullable = false)
    @ColumnDefault("0")
    private Long parentCostCenterId;

    /**
     * Parent cost center relationship.
     */
    @ManyToOne
    @NotFound(action = NotFoundAction.IGNORE)
    @JoinColumn(name = "parent_cost_center_id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(value = ConstraintMode.NO_CONSTRAINT))
    private CostCenter parentCostCenter;

    /**
     * ID of the origin municipality/country.
     * Original field: CENTROCOSTOS_MPORIGEN_ID
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