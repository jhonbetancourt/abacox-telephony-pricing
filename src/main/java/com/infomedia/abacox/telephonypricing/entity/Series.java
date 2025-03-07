package com.infomedia.abacox.telephonypricing.entity;

import com.infomedia.abacox.telephonypricing.entity.superclass.AuditedEntity;
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
public class Series extends AuditedEntity {

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
            initialValue = 1000000
    )
    @Column(name = "id", nullable = false)
    private Long id;

    /**
     * ID of the associated indicator/area code.
     * Original field: SERIE_INDICATIVO_ID
     */
    //TODO: This should be a foreign key to the Indicator entity
    @Column(name = "indicator_id", nullable = false)
    @ColumnDefault("0")
    private Long indicatorId;

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