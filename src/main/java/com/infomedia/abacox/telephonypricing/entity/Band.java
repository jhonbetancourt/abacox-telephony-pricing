package com.infomedia.abacox.telephonypricing.entity;

import com.infomedia.abacox.telephonypricing.entity.superclass.ActivableEntity;
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
public class Band extends ActivableEntity {

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
            initialValue = 10000000
    )
    @Column(name = "id", nullable = false)
    private Long id;

    /**
     * ID of the associated prefix.
     * Original field: BANDA_PREFIJO_ID
     */
    @Column(name = "prefix_id", nullable = true)
    private Long prefixId;

    /**
     * Prefix relationship.
     */
    @ManyToOne
    @JoinColumn(
            name = "prefix_id",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_band_prefix")
    )
    private Prefix prefix;

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
    @Column(name = "value", nullable = false, precision = 10, scale = 4)
    @ColumnDefault("0")
    private BigDecimal value;

    /**
     * ID of the origin indicator.
     * Original field: BANDA_INDICAORIGEN_ID
     */
    @Column(name = "origin_indicator_id")
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
            foreignKey = @ForeignKey(name = "fk_band_origin_indicator")
    )
    private Indicator originIndicator;

    /**
     * Flag indicating if VAT is included in the price.
     * Original field: BANDA_IVAINC
     */
    @Column(name = "vat_included", nullable = false)
    @ColumnDefault("false")
    private Boolean vatIncluded;


    /**
     * Reference number.
     * Original field: BANDA_REF
     */
    @Column(name = "reference", nullable = false)
    @ColumnDefault("0")
    private Long reference;
}