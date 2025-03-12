package com.infomedia.abacox.telephonypricing.entity;

import com.infomedia.abacox.telephonypricing.entity.superclass.ActivableEntity;
import jakarta.persistence.*;
import lombok.*;

import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnDefault;

/**
 * Entity representing country/origin information.
 * Original table name: MPORIGEN
 */
@Entity
@Table(name = "origin_country")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class OriginCountry extends ActivableEntity {

    /**
     * Primary key for the country.
     * Original field: MPORIGEN_ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "origin_country_id_seq")
    @SequenceGenerator(
            name = "origin_country_id_seq",
            sequenceName = "origin_country_id_seq",
            allocationSize = 1,
            initialValue = 1000000
    )
    @Column(name = "id", nullable = false)
    private Long id;

    /**
     * Symbol for the currency.
     * Original field: MPORIGEN_SIMBOLO
     */
    @Column(name = "currency_symbol", length = 10, nullable = false)
    @ColumnDefault("$")
    private String currencySymbol;

    /**
     * Country name.
     * Original field: MPORIGEN_PAIS
     */
    @Column(name = "name", length = 50, nullable = false)
    @ColumnDefault("")
    private String name;

    /**
     * Country code (typically ISO code).
     * Original field: MPORIGEN_CCODE
     */
    @Column(name = "code", length = 3, nullable = false)
    @ColumnDefault("")
    private String code;
    
    /**
     * ID of the area code/indicator (commented out in original schema).
     * Original field: MPORIGEN_INDICATIVO_ID
     */
    // @Column(name = "indicator_id", nullable = false)
    // @ColumnDefault("0")
    // private Integer indicatorId;
}