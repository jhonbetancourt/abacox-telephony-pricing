package com.infomedia.abacox.telephonypricing.entity;

import com.infomedia.abacox.telephonypricing.entity.superclass.AuditedEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnDefault;

/**
 * Entity representing band/range configurations for telephone services.
 * Original table name: BANDA
 */
@Entity
@Table(name = "band")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class Band extends AuditedEntity {

    /**
     * Primary key for the band.
     * Original field: BANDA_ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "band_id_seq")
    @SequenceGenerator(
            name = "band_id_seq",
            sequenceName = "band_id_seq",
            allocationSize = 1,
            initialValue = 1000000
    )
    @Column(name = "id", nullable = false)
    private Long id;

    /**
     * ID of the associated prefix.
     * Original field: BANDA_PREFIJO_ID
     */
    //TODO: This should be a foreign key to the Prefix entity
    @Column(name = "prefix_id", nullable = false)
    @ColumnDefault("0")
    private Long prefixId;

    /**
     * Name of the band.
     * Original field: BANDA_NOMBRE
     */
    @Column(name = "name", length = 50, nullable = false)
    @ColumnDefault("")
    private String name;

    /**
     * Value/cost associated with the band.
     * Original field: BANDA_VALOR
     */
    @Column(name = "value", nullable = false)
    @ColumnDefault("0")
    private BigDecimal value;

    /**
     * Flag indicating if VAT is included in the price.
     * Original field: BANDA_IVAINC
     */
    @Column(name = "vat_included", nullable = false)
    @ColumnDefault("false")
    private boolean vatIncluded;

    /**
     * ID indicating the origin of the band.
     * Original field: BANDA_INDICAORIGEN_ID
     */
    @Column(name = "origin_indicator_id", nullable = false)
    @ColumnDefault("0")
    private Long originIndicatorId;

    /**
     * Minimum distance for this band.
     * Original field: BANDA_DISTMIN
     */
    @Column(name = "min_distance", nullable = false)
    @ColumnDefault("0")
    private Integer minDistance;

    /**
     * Maximum distance for this band.
     * Original field: BANDA_DISTMAX
     */
    @Column(name = "max_distance", nullable = false)
    @ColumnDefault("0")
    private Integer maxDistance;

    /**
     * ID of the band group this band belongs to.
     * Original field: BANDA_BANDAGRUPO_ID
     */
    @Column(name = "band_group_id", nullable = false)
    @ColumnDefault("0")
    private Long bandGroupId;
}