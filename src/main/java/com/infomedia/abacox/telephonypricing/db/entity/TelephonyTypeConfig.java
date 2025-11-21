package com.infomedia.abacox.telephonypricing.db.entity;

import com.infomedia.abacox.telephonypricing.db.entity.superclass.ActivableEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnDefault;

/**
 * Entity representing telephony type configuration.
 * Original table name: tipotelecfg
 */
@Entity
@Table(name = "telephony_type_config")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class TelephonyTypeConfig extends ActivableEntity {

    /**
     * Primary key for the telephony type configuration.
     * Original field: TIPOTELECFG_ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "telephony_type_config_id_seq")
    @SequenceGenerator(
            name = "telephony_type_config_id_seq",
            sequenceName = "telephony_type_config_id_seq",
            allocationSize = 1,
            initialValue = 10000000
    )
    @Column(name = "id", nullable = false)
    private Long id;

    /**
     * Minimum value for the configuration range.
     * Original field: TIPOTELECFG_MIN
     */
    @Column(name = "min_value", nullable = false)
    @ColumnDefault("0")
    private Integer minValue;

    /**
     * Maximum value for the configuration range.
     * Original field: TIPOTELECFG_MAX
     */
    @Column(name = "max_value", nullable = false)
    @ColumnDefault("0")
    private Integer maxValue;

    /**
     * ID of the telephony type.
     * Original field: TIPOTELECFG_TIPOTELE_ID
     */
    @Column(name = "telephony_type_id")
    private Long telephonyTypeId;

    /**
     * Telephony type relationship.
     */
    @ManyToOne
    @JoinColumn(
            name = "telephony_type_id",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_telephony_type_config_telephony_type")
    )
    private TelephonyType telephonyType;

    /**
     * ID of the origin country.
     * Original field: TIPOTELECFG_MPORIGEN_ID
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
            foreignKey = @ForeignKey(name = "fk_telephony_type_config_origin_country")
    )
    private OriginCountry originCountry;
}