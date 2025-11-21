package com.infomedia.abacox.telephonypricing.db.entity;

import com.infomedia.abacox.telephonypricing.db.entity.superclass.ActivableEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnDefault;
import java.math.BigDecimal;

@Entity
@Table(
    name = "special_service",
    indexes = {
        // Used in SpecialServiceLookupService::findSpecialService
        // WHERE phone_number = ? AND indicator_id = ?
        @Index(name = "idx_special_service_lookup", columnList = "phone_number, indicator_id, active")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class SpecialService extends ActivableEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "special_service_id_seq")
    @SequenceGenerator(name = "special_service_id_seq", sequenceName = "special_service_id_seq", allocationSize = 1, initialValue = 10000000)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "indicator_id")
    private Long indicatorId;

    @ManyToOne
    @JoinColumn(name = "indicator_id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_special_service_indicator"))
    private Indicator indicator;

    @Column(name = "phone_number", length = 50, nullable = false)
    @ColumnDefault("''")
    private String phoneNumber;

    @Column(name = "value", nullable = false, precision = 10, scale = 4)
    @ColumnDefault("0")
    private BigDecimal value;

    @Column(name = "vat_amount", nullable = false, precision = 10, scale = 4)
    @ColumnDefault("0")
    private BigDecimal vatAmount;

    @Column(name = "vat_included", nullable = false)
    @ColumnDefault("false")
    private Boolean vatIncluded;

    @Column(name = "description", length = 80, nullable = false)
    @ColumnDefault("''")
    private String description;

    @Column(name = "origin_country_id")
    private Long originCountryId;

    @ManyToOne
    @JoinColumn(name = "origin_country_id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_special_service_origin_country"))
    private OriginCountry originCountry;
}