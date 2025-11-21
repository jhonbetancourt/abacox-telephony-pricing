package com.infomedia.abacox.telephonypricing.db.entity;

import com.infomedia.abacox.telephonypricing.db.entity.superclass.ActivableEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnDefault;
import java.math.BigDecimal;

@Entity
@Table(
    name = "trunk_rate",
    indexes = {
        // Optimizes TrunkLookupService.getRateDetailsForTrunk
        // WHERE trunk_id = ? AND telephony_type_id = ? AND operator_id = ? AND active = true
        @Index(name = "idx_trunk_rate_lookup", columnList = "trunk_id, telephony_type_id, operator_id, active"),
        
        // Fallback FK indexes
        @Index(name = "idx_trunk_rate_operator", columnList = "operator_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class TrunkRate extends ActivableEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "trunk_rate_id_seq")
    @SequenceGenerator(name = "trunk_rate_id_seq", sequenceName = "trunk_rate_id_seq", allocationSize = 1, initialValue = 10000000)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "trunk_id")
    private Long trunkId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trunk_id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_trunk_rate_trunk"))
    private Trunk trunk;

    @Column(name = "rate_value", nullable = false, precision = 10, scale = 4)
    @ColumnDefault("0")
    private BigDecimal rateValue;

    @Column(name = "includes_vat", nullable = false)
    @ColumnDefault("false")
    private Boolean includesVat;

    @Column(name = "operator_id")
    private Long operatorId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "operator_id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_trunk_rate_operator"))
    private Operator operator;

    @Column(name = "telephony_type_id")
    private Long telephonyTypeId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "telephony_type_id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_trunk_rate_telephony_type"))
    private TelephonyType telephonyType;

    @Column(name = "no_pbx_prefix", nullable = false)
    @ColumnDefault("true")
    private Boolean noPbxPrefix;

    @Column(name = "no_prefix", nullable = false)
    @ColumnDefault("true")
    private Boolean noPrefix;

    @Column(name = "seconds", nullable = false)
    @ColumnDefault("0")
    private Integer seconds;
}