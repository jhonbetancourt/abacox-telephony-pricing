package com.infomedia.abacox.telephonypricing.db.entity;

import com.infomedia.abacox.telephonypricing.db.entity.superclass.ActivableEntity;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnDefault;

@Entity
@Table(
    name = "band",
    indexes = {
        // Used in PrefixLookupService to find rates
        @Index(name = "idx_band_prefix_active", columnList = "prefix_id, active"),
        // Used in SpecialRateValueLookupService
        @Index(name = "idx_band_origin_ind", columnList = "origin_indicator_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class Band extends ActivableEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "band_id_seq")
    @SequenceGenerator(name = "band_id_seq", sequenceName = "band_id_seq", allocationSize = 1, initialValue = 10000000)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "prefix_id")
    private Long prefixId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "prefix_id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_band_prefix"))
    private Prefix prefix;

    @Column(name = "name", length = 50, nullable = false)
    @ColumnDefault("''")
    private String name;

    @Column(name = "value", nullable = false, precision = 10, scale = 4)
    @ColumnDefault("0")
    private BigDecimal value;

    @Column(name = "origin_indicator_id")
    private Long originIndicatorId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "origin_indicator_id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_band_origin_indicator"))
    private Indicator originIndicator;

    @Column(name = "vat_included", nullable = false)
    @ColumnDefault("false")
    private Boolean vatIncluded;

    @Column(name = "reference", nullable = false)
    @ColumnDefault("0")
    private Long reference;
}