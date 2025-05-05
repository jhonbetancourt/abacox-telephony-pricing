package com.infomedia.abacox.telephonypricing.entity;

import com.infomedia.abacox.telephonypricing.entity.superclass.ActivableEntity;
import com.infomedia.abacox.telephonypricing.entity.superclass.AuditedEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnDefault;

import java.math.BigDecimal;

/**
 * Entity representing trunk rate/tariff information.
 * Original table name: tarifatroncal
 */
@Entity
@Table(name = "trunk_rate")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class TrunkRate extends ActivableEntity {

    /**
     * Primary key for the trunk rate.
     * Original field: TARIFATRONCAL_ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "trunk_rate_id_seq")
    @SequenceGenerator(
            name = "trunk_rate_id_seq",
            sequenceName = "trunk_rate_id_seq",
            allocationSize = 1,
            initialValue = 10000000
    )
    @Column(name = "id", nullable = false)
    private Long id;

    /**
     * ID of the trunk.
     * Original field: TARIFATRONCAL_TRONCAL_ID
     */
    @Column(name = "trunk_id")
    private Long trunkId;

    /**
     * Trunk relationship.
     */
    @ManyToOne
    @JoinColumn(
            name = "trunk_id",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_trunk_rate_trunk")
    )
    private Trunk trunk;

    /**
     * Rate value/amount.
     * Original field: TARIFATRONCAL_VALOR
     */
    @Column(name = "rate_value", nullable = false, precision = 10, scale = 4)
    @ColumnDefault("0")
    private BigDecimal rateValue;

    /**
     * Flag indicating if VAT/tax is included.
     * Original field: TARIFATRONCAL_IVAINC
     */
    @Column(name = "includes_vat", nullable = false)
    @ColumnDefault("false")
    private Boolean includesVat;

    /**
     * ID of the operator.
     * Original field: TARIFATRONCAL_OPERADOR_ID
     */
    @Column(name = "operator_id")
    private Long operatorId;

    /**
     * Operator relationship.
     */
    @ManyToOne
    @JoinColumn(
            name = "operator_id",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_trunk_rate_operator")
    )
    private Operator operator;

    /**
     * ID of the telephony type.
     * Original field: TARIFATRONCAL_TIPOTELE_ID
     */
    @Column(name = "telephony_type_id")
    private Long telephonyTypeId;

    /**
     * Telephony type relationship.
     */
    @ManyToOne
    @JoinColumn(
            name = "telephony_type_id",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_trunk_rate_telephony_type")
    )
    private TelephonyType telephonyType;

    /**
     * Flag indicating if PBX prefix should not be used.
     * Original field: TARIFATRONCAL_NOPREFIJOPBX
     */
    @Column(name = "no_pbx_prefix", nullable = false)
    @ColumnDefault("true")
    private Boolean noPbxPrefix;

    /**
     * Flag indicating if prefix should not be used.
     * Original field: TARIFATRONCAL_NOPREFIJO
     */
    @Column(name = "no_prefix", nullable = false)
    @ColumnDefault("true")
    private Boolean noPrefix;

    /**
     * Duration in seconds.
     * Original field: TARIFATRONCAL_SEGUNDOS
     */
    @Column(name = "seconds", nullable = false)
    @ColumnDefault("0")
    private Integer seconds;
}