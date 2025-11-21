package com.infomedia.abacox.telephonypricing.db.entity;

import com.infomedia.abacox.telephonypricing.db.entity.superclass.ActivableEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnDefault;

@Entity
@Table(
    name = "telephony_type_config",
    indexes = {
        // Used in TelephonyTypeLookupService and PrefixLookupService
        // WHERE telephony_type_id = ? AND origin_country_id = ? AND active = true
        @Index(name = "idx_tt_config_lookup", columnList = "telephony_type_id, origin_country_id, active")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class TelephonyTypeConfig extends ActivableEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "telephony_type_config_id_seq")
    @SequenceGenerator(name = "telephony_type_config_id_seq", sequenceName = "telephony_type_config_id_seq", allocationSize = 1, initialValue = 10000000)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "min_value", nullable = false)
    @ColumnDefault("0")
    private Integer minValue;

    @Column(name = "max_value", nullable = false)
    @ColumnDefault("0")
    private Integer maxValue;

    @Column(name = "telephony_type_id")
    private Long telephonyTypeId;

    @ManyToOne
    @JoinColumn(name = "telephony_type_id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_telephony_type_config_telephony_type"))
    private TelephonyType telephonyType;

    @Column(name = "origin_country_id")
    private Long originCountryId;

    @ManyToOne
    @JoinColumn(name = "origin_country_id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_telephony_type_config_origin_country"))
    private OriginCountry originCountry;
}