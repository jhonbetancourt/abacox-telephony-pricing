package com.infomedia.abacox.telephonypricing.db.entity;

import com.infomedia.abacox.telephonypricing.db.entity.superclass.AuditedEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

@Entity
@Table(
    name = "band_indicator",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_band_indicator_band_indicator", columnNames = {"band_id", "indicator_id"})
    },
    indexes = {
        // Critical for IndicatorLookupService JOIN (indicator -> band_indicator -> band)
        @Index(name = "idx_band_ind_indicator", columnList = "indicator_id"),
        @Index(name = "idx_band_ind_band", columnList = "band_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class BandIndicator extends AuditedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "band_indicator_id_seq")
    @SequenceGenerator(name = "band_indicator_id_seq", sequenceName = "band_indicator_id_seq", allocationSize = 1, initialValue = 10000000)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "band_id")
    private Long bandId;

    @ManyToOne
    @JoinColumn(name = "band_id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_band_indicator_band"))
    private Band band;

    @Column(name = "indicator_id")
    private Long indicatorId;

    @ManyToOne
    @JoinColumn(name = "indicator_id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_band_indicator_indicator"))
    private Indicator indicator;
}