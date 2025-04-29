package com.infomedia.abacox.telephonypricing.entity;

import com.infomedia.abacox.telephonypricing.entity.superclass.ActivableEntity;
import com.infomedia.abacox.telephonypricing.entity.superclass.AuditedEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnDefault;

import java.math.BigDecimal;

/**
 * Entity representing special services.
 * Original table name: servespecial
 */
@Entity
@Table(name = "special_service")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class SpecialService extends ActivableEntity {

    /**
     * Primary key for the special service.
     * Original field: SERVESPECIAL_ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "special_service_id_seq")
    @SequenceGenerator(
            name = "special_service_id_seq",
            sequenceName = "special_service_id_seq",
            allocationSize = 1,
            initialValue = 10000000
    )
    @Column(name = "id", nullable = false)
    private Long id;

    /**
     * ID of the indicator.
     * Original field: SERVESPECIAL_INDICATIVO_ID
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
            foreignKey = @ForeignKey(name = "fk_special_service_indicator")
    )
    private Indicator indicator;

    /**
     * Phone number for the special service.
     * Original field: SERVESPECIAL_NUMERO
     */
    @Column(name = "phone_number", length = 50, nullable = false)
    @ColumnDefault("''")
    private String phoneNumber;

    /**
     * Value/cost of the special service.
     * Original field: SERVESPECIAL_VALOR
     */
    @Column(name = "value", nullable = false, precision = 10, scale = 4)
    @ColumnDefault("0")
    private BigDecimal value;

    /**
     * VAT/tax amount.
     * Original field: SERVESPECIAL_IVA
     */
    @Column(name = "vat_amount", nullable = false, precision = 10, scale = 4)
    @ColumnDefault("0")
    private BigDecimal vatAmount;

    /**
     * Flag indicating if VAT is included in the price.
     * Original field: SERVESPECIAL_IVAINC
     */
    @Column(name = "vat_included", nullable = false)
    @ColumnDefault("false")
    private Boolean vatIncluded;

    /**
     * Description of the special service.
     * Original field: SERVESPECIAL_DESCRIPCION
     */
    @Column(name = "description", length = 80, nullable = false)
    @ColumnDefault("''")
    private String description;

    /**
     * ID of the origin municipality/country.
     * Original field: SERVESPECIAL_MPORIGEN_ID
     */
    @Column(name = "origin_country_id")
    private Long originCountryId;

    /**
     * Origin country relationship.
     */
    @ManyToOne
    @JoinColumn(
            name = "origin_country_id",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_special_service_origin_country")
    )
    private OriginCountry originCountry;
}