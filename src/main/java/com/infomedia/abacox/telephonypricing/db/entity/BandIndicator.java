package com.infomedia.abacox.telephonypricing.db.entity;

import com.infomedia.abacox.telephonypricing.db.entity.superclass.AuditedEntity;
import jakarta.persistence.*;
import lombok.*;


import lombok.experimental.SuperBuilder;

/**
 * Entity representing the relationship between bands and indicators.
 * Original table name: BANDAINDICA
 */
@Entity
@Table(
    name = "band_indicator",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_band_indicator_band_indicator",
            columnNames = {"band_id", "indicator_id"}
        )
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class BandIndicator extends AuditedEntity {

    /**
     * Primary key for the band indicator relationship.
     * Original field: BANDAINDICA_ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "band_indicator_id_seq")
    @SequenceGenerator(
            name = "band_indicator_id_seq",
            sequenceName = "band_indicator_id_seq",
            allocationSize = 1,
            initialValue = 10000000
    )
    @Column(name = "id", nullable = false)
    private Long id;

    /**
     * ID of the associated band.
     * Original field: BANDAINDICA_BANDA_ID
     */
    @Column(name = "band_id")
    private Long bandId;

    /**
     * Band relationship.
     */
    @ManyToOne
    @JoinColumn(
            name = "band_id", 
            insertable = false, 
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_band_indicator_band")
    )
    private Band band;

    /**
     * ID of the associated indicator.
     * Original field: BANDAINDICA_INDICATIVO_ID
     */
    @Column(name = "indicator_id")
    private Long indicatorId;

    /**
     * Indicator relationship.
     */
    @ManyToOne
    @JoinColumn(
            name = "indicator_id", 
            insertable = false, 
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_band_indicator_indicator")
    )
    private Indicator indicator;
}