package com.infomedia.abacox.telephonypricing.db.entity;

import com.infomedia.abacox.telephonypricing.db.entity.superclass.ActivableEntity;
import jakarta.persistence.*;
import lombok.*;

import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnDefault;

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
public class CostCenter extends ActivableEntity {

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
            initialValue = 10000000
    )
    @Column(name = "id", nullable = false)
    private Long id;

    /**
     * Name or identifier of the cost center.
     * Original field: CENTROCOSTOS_CENTRO_COSTO
     */
    @Column(name = "name", length = 100, nullable = false)
    @ColumnDefault("")
    private String name;

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
    @Column(name = "parent_cost_center_id")
    private Long parentCostCenterId;

    /**
     * Parent cost center relationship.
     */
    @ManyToOne
    @JoinColumn(
            name = "parent_cost_center_id",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_cost_center_parent")
    )
    private CostCenter parentCostCenter;

    /**
     * ID of the origin municipality/country.
     * Original field: CENTROCOSTOS_MPORIGEN_ID
     */
    @Column(name = "origin_country_id")
    private Long originCountryId;

    /**
     * Origin country relationship.
     */
    @ManyToOne
    @JoinColumn(
            name = "origin_country_id",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_cost_center_origin_country")
    )
    private OriginCountry originCountry;
}