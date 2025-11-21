// src/main/java/com/infomedia/abacox/telephonypricing/db/entity/TrunkRule.java
package com.infomedia.abacox.telephonypricing.db.entity;

import com.infomedia.abacox.telephonypricing.db.entity.superclass.ActivableEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnDefault;

import java.math.BigDecimal;

@Entity
@Table(
    name = "trunk_rule",
    indexes = {
        // Critical for getAppliedTrunkRule query
        @Index(name = "idx_trunk_rule_lookup", columnList = "trunk_id, telephony_type_id, origin_indicator_id, active")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class TrunkRule extends ActivableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "trunk_rule_id_seq")
    @SequenceGenerator(
            name = "trunk_rule_id_seq",
            sequenceName = "trunk_rule_id_seq",
            allocationSize = 1,
            initialValue = 10000000
    )
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "rate_value", nullable = false, precision = 10, scale = 4)
    @ColumnDefault("0")
    private BigDecimal rateValue;

    @Column(name = "includes_vat", nullable = false)
    @ColumnDefault("false")
    private Boolean includesVat;

    @Column(name = "telephony_type_id")
    private Long telephonyTypeId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "telephony_type_id",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_trunk_rule_telephony_type")
    )
    private TelephonyType telephonyType;

    @Column(name = "indicator_ids", length = 255, nullable = false)
    @ColumnDefault("''")
    private String indicatorIds;

    @Column(name = "trunk_id")
    private Long trunkId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "trunk_id",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_trunk_rule_trunk")
    )
    private Trunk trunk;

    @Column(name = "new_operator_id", nullable = false)
    @ColumnDefault("0")
    private Long newOperatorId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "new_operator_id",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_trunk_rule_new_operator")
    )
    private Operator newOperator;

    @Column(name = "new_telephony_type_id", nullable = false)
    @ColumnDefault("0")
    private Long newTelephonyTypeId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "new_telephony_type_id",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_trunk_rule_new_telephony_type")
    )
    private TelephonyType newTelephonyType;

    @Column(name = "seconds", nullable = false)
    @ColumnDefault("0")
    private Integer seconds;

    @Column(name = "origin_indicator_id")
    private Long originIndicatorId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "origin_indicator_id",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_trunk_rule_origin_indicator")
    )
    private Indicator originIndicator;
}