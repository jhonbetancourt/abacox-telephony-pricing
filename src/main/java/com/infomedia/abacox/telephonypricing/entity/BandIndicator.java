package com.infomedia.abacox.telephonypricing.entity;

import com.infomedia.abacox.telephonypricing.entity.superclass.AuditedEntity;
import jakarta.persistence.*;
import lombok.*;


import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnDefault;

/**
 * Entity representing the relationship between bands and indicators.
 * Original table name: BANDAINDICA
 */
@Entity
@Table(name = "band_indicator")
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
            initialValue = 1000000
    )
    @Column(name = "id", nullable = false)
    private Long id;

    /**
     * ID of the associated band.
     * Original field: BANDAINDICA_BANDA_ID
     */
    //TODO: This should be a foreign key to the Band entity
    @Column(name = "band_id", nullable = false)
    @ColumnDefault("0")
    private Long bandId;

    /**
     * ID of the associated indicator.
     * Original field: BANDAINDICA_INDICATIVO_ID
     */
    //TODO: This should be a foreign key to the Indicator entity
    @Column(name = "indicator_id", nullable = false)
    @ColumnDefault("0")
    private Long indicatorId;
}