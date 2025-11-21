// src/main/java/com/infomedia/abacox/telephonypricing/db/entity/Series.java
package com.infomedia.abacox.telephonypricing.db.entity;

import com.infomedia.abacox.telephonypricing.db.entity.superclass.ActivableEntity;
import jakarta.persistence.*;
import lombok.*;

import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnDefault;

@Entity
@Table(
    name = "series",
    indexes = {
        // Critical for IndicatorLookupService (findDestinationIndicator, findLocalNdcForIndicator)
        @Index(name = "idx_series_indicator_active", columnList = "indicator_id, active"),
        
        // Optimizes lookups by NDC (used in finding destination)
        @Index(name = "idx_series_ndc", columnList = "ndc")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class Series extends ActivableEntity {

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

    @Column(name = "indicator_id")
    private Long indicatorId;

    @ManyToOne
    @JoinColumn(
            name = "indicator_id", 
            insertable = false, 
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_series_indicator")
    )
    private Indicator indicator;

    @Column(name = "ndc", nullable = false)
    @ColumnDefault("0")
    private Integer ndc;

    @Column(name = "initial_number", nullable = false)
    @ColumnDefault("0")
    private Integer initialNumber;

    @Column(name = "final_number", nullable = false)
    @ColumnDefault("0")
    private Integer finalNumber;

    @Column(name = "company", length = 200)
    private String company;
}