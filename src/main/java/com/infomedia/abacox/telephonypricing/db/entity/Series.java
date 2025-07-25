package com.infomedia.abacox.telephonypricing.db.entity;

import com.infomedia.abacox.telephonypricing.db.entity.superclass.ActivableEntity;
import jakarta.persistence.*;
import lombok.*;

import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnDefault;

/**
 * Entity representing number series ranges for telephone indicators.
 * Original table name: SERIE
 */
@Entity
@Table(name = "series")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class Series extends ActivableEntity {

    /**
     * Primary key for the series.
     * Original field: SERIE_ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "series_id_seq")
    @SequenceGenerator(
            name = "series_id_seq",
            sequenceName = "series_id_seq",
            allocationSize = 1,
            initialValue = 10000000
    )
    @Column(name = "id", nullable = false)
    private Long id;

    /**
     * ID of the associated indicator/area code.
     * Original field: SERIE_INDICATIVO_ID
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
            foreignKey = @ForeignKey(name = "fk_series_indicator")
    )
    private Indicator indicator;

    /**
     * NDC (National Destination Code) for this series.
     * Original field: SERIE_NDC
     */
    @Column(name = "ndc", nullable = false)
    @ColumnDefault("0")
    private Integer ndc;

    /**
     * Initial number in the series range.
     * Original field: SERIE_INICIAL
     */
    @Column(name = "initial_number", nullable = false)
    @ColumnDefault("0")
    private Integer initialNumber;

    /**
     * Final number in the series range.
     * Original field: SERIE_FINAL
     */
    @Column(name = "final_number", nullable = false)
    @ColumnDefault("0")
    private Integer finalNumber;

    /**
     * Company associated with this number series.
     * Original field: SERIE_EMPRESA
     * Note: This field was nullable in the original schema.
     */
    @Column(name = "company", length = 200)
    private String company;
}