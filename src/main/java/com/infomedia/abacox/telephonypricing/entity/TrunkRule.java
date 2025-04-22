package com.infomedia.abacox.telephonypricing.entity;

import com.infomedia.abacox.telephonypricing.entity.superclass.ActivableEntity;
import com.infomedia.abacox.telephonypricing.entity.superclass.AuditedEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnDefault;

import java.math.BigDecimal;

/**
 * Entity representing trunk routing rules.
 * Original table name: reglatroncal
 */
@Entity
@Table(name = "trunk_rule")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class TrunkRule extends ActivableEntity {

    /**
     * Primary key for the trunk rule.
     * Original field: REGLATRONCAL_ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "trunk_rule_id_seq")
    @SequenceGenerator(
            name = "trunk_rule_id_seq",
            sequenceName = "trunk_rule_id_seq",
            allocationSize = 1,
            initialValue = 1000000
    )
    @Column(name = "id", nullable = false)
    private Long id;

    /**
     * Rate value/amount.
     * Original field: REGLATRONCAL_VALOR
     */
    @Column(name = "rate_value", nullable = false, precision = 10, scale = 4)
    @ColumnDefault("0")
    private BigDecimal rateValue;

    /**
     * Flag indicating if VAT/tax is included.
     * Original field: REGLATRONCAL_IVAINC
     */
    @Column(name = "includes_vat", nullable = false)
    @ColumnDefault("false")
    private Boolean includesVat;

    /**
     * ID of the telephony type.
     * Original field: REGLATRONCAL_TIPOTELE_ID
     */
    @Column(name = "telephony_type_id", nullable = false)
    @ColumnDefault("0")
    private Long telephonyTypeId;

    /**
     * Telephony type relationship.
     */
    @ManyToOne
    @JoinColumn(
            name = "telephony_type_id",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_trunk_rule_telephony_type")
    )
    private TelephonyType telephonyType;

    /**
     * Indicator IDs, comma-separated string.
     * Original field: REGLATRONCAL_INDICATIVO_ID
     */
    @Column(name = "indicator_ids", length = 255, nullable = false)
    @ColumnDefault("''")
    private String indicatorIds;

    /**
     * ID of the trunk.
     * Original field: REGLATRONCAL_TRONCAL_ID
     */
    @Column(name = "trunk_id", nullable = false)
    @ColumnDefault("0")
    private Long trunkId;

    /**
     * Trunk relationship.
     */
    @ManyToOne
    @JoinColumn(
            name = "trunk_id",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_trunk_rule_trunk")
    )
    private Trunk trunk;

    /**
     * ID of the new operator to route to.
     * Original field: REGLATRONCAL_OPERADOR_NUEVO
     */
    @Column(name = "new_operator_id", nullable = false)
    @ColumnDefault("0")
    private Long newOperatorId;

    /**
     * New operator relationship.
     */
    @ManyToOne
    @JoinColumn(
            name = "new_operator_id",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_trunk_rule_new_operator")
    )
    private Operator newOperator;

    /**
     * ID of the new telephony type to route to.
     * Original field: REGLATRONCAL_TIPOTELE_NUEVO
     */
    @Column(name = "new_telephony_type_id", nullable = false)
    @ColumnDefault("0")
    private Long newTelephonyTypeId;

    /**
     * New telephony type relationship.
     */
    @ManyToOne
    @JoinColumn(
            name = "new_telephony_type_id",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_trunk_rule_new_telephony_type")
    )
    private TelephonyType newTelephonyType;

    /**
     * Duration in seconds.
     * Original field: REGLATRONCAL_SEGUNDOS
     */
    @Column(name = "seconds", nullable = false)
    @ColumnDefault("0")
    private Integer seconds;

    /**
     * ID of the origin indicator.
     * Original field: REGLATRONCAL_INDICAORIGEN_ID
     */
    @Column(name = "origin_indicator_id", nullable = false)
    @ColumnDefault("0")
    private Long originIndicatorId;

    /**
     * Origin indicator relationship.
     */
    @ManyToOne
    @JoinColumn(
            name = "origin_indicator_id",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_trunk_rule_origin_indicator")
    )
    private Indicator originIndicator;
}