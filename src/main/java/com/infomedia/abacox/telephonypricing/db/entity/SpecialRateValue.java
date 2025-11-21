// src/main/java/com/infomedia/abacox/telephonypricing/db/entity/SpecialRateValue.java
package com.infomedia.abacox.telephonypricing.db.entity;

import com.infomedia.abacox.telephonypricing.db.entity.superclass.ActivableEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnDefault;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Entity representing special rate values.
 * Original table name: valorespecial
 */
@Entity
@Table(
    name = "special_rate_value",
    indexes = {
        // 1. Rate Lookup: The query in SpecialRateValueLookupService filters heavily on
        // Date Range + Telephony Type + Operator + Band + Origin Indicator.
        @Index(name = "idx_special_rate_lookup", columnList = "valid_from, valid_to, telephony_type_id, operator_id, band_id, origin_indicator_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class SpecialRateValue extends ActivableEntity {

    /**
     * Primary key for the special rate value.
     * Original field: VALORESPECIAL_ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "special_rate_value_id_seq")
    @SequenceGenerator(
            name = "special_rate_value_id_seq",
            sequenceName = "special_rate_value_id_seq",
            allocationSize = 1,
            initialValue = 10000000
    )
    @Column(name = "id", nullable = false)
    private Long id;

    /**
     * Name of the special rate.
     * Original field: VALORESPECIAL_NOMBRE
     */
    @Column(name = "name", length = 100, nullable = false)
    @ColumnDefault("''")
    private String name;

    /**
     * Rate value/amount.
     * Original field: VALORESPECIAL_VALOR
     */
    @Column(name = "rate_value", nullable = false, precision = 10, scale = 4)
    @ColumnDefault("0")
    private BigDecimal rateValue;

    /**
     * Flag indicating if VAT/tax is included.
     * Original field: VALORESPECIAL_IVAINC
     */
    @Column(name = "includes_vat", nullable = false)
    @ColumnDefault("false")
    private Boolean includesVat;

    /**
     * Flag indicating if the rate applies on Sunday.
     * Original field: VALORESPECIAL_DOMINGO
     */
    @Column(name = "sunday_enabled", nullable = false)
    @ColumnDefault("true")
    private Boolean sundayEnabled;

    /**
     * Flag indicating if the rate applies on Monday.
     * Original field: VALORESPECIAL_LUNES
     */
    @Column(name = "monday_enabled", nullable = false)
    @ColumnDefault("true")
    private Boolean mondayEnabled;

    /**
     * Flag indicating if the rate applies on Tuesday.
     * Original field: VALORESPECIAL_MARTES
     */
    @Column(name = "tuesday_enabled", nullable = false)
    @ColumnDefault("true")
    private Boolean tuesdayEnabled;

    /**
     * Flag indicating if the rate applies on Wednesday.
     * Original field: VALORESPECIAL_MIERCOLES
     */
    @Column(name = "wednesday_enabled", nullable = false)
    @ColumnDefault("true")
    private Boolean wednesdayEnabled;

    /**
     * Flag indicating if the rate applies on Thursday.
     * Original field: VALORESPECIAL_JUEVES
     */
    @Column(name = "thursday_enabled", nullable = false)
    @ColumnDefault("true")
    private Boolean thursdayEnabled;

    /**
     * Flag indicating if the rate applies on Friday.
     * Original field: VALORESPECIAL_VIERNES
     */
    @Column(name = "friday_enabled", nullable = false)
    @ColumnDefault("true")
    private Boolean fridayEnabled;

    /**
     * Flag indicating if the rate applies on Saturday.
     * Original field: VALORESPECIAL_SABADO
     */
    @Column(name = "saturday_enabled", nullable = false)
    @ColumnDefault("true")
    private Boolean saturdayEnabled;

    /**
     * Flag indicating if the rate applies on holidays.
     * Original field: VALORESPECIAL_FESTIVO
     */
    @Column(name = "holiday_enabled", nullable = false)
    @ColumnDefault("true")
    private Boolean holidayEnabled;

    /**
     * ID of the telephony type.
     * Original field: VALORESPECIAL_TIPOTELE_ID
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
            foreignKey = @ForeignKey(name = "fk_special_rate_value_telephony_type")
    )
    private TelephonyType telephonyType;

    /**
     * ID of the operator.
     * Original field: VALORESPECIAL_OPERADOR_ID
     */
    @Column(name = "operator_id")
    private Long operatorId;

    /**
     * Operator relationship.
     */
    @ManyToOne
    @JoinColumn(
            name = "operator_id",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_special_rate_value_operator")
    )
    private Operator operator;

    /**
     * ID of the band.
     * Original field: VALORESPECIAL_BANDA_ID
     */
    @Column(name = "band_id")
    private Long bandId;

    /**
     * Band relationship.
     */
    @ManyToOne
    @JoinColumn(
            name = "band_id",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_special_rate_value_band")
    )
    private Band band;

    /**
     * Start date/time of validity.
     * Original field: VALORESPECIAL_DESDE
     */
    @Column(name = "valid_from")
    private LocalDateTime validFrom;

    /**
     * End date/time of validity.
     * Original field: VALORESPECIAL_HASTA
     */
    @Column(name = "valid_to")
    private LocalDateTime validTo;

    /**
     * ID of the origin indicator.
     * Original field: VALORESPECIAL_INDICAORIGEN_ID
     */
    @Column(name = "origin_indicator_id")
    private Long originIndicatorId;

    /**
     * Origin indicator relationship.
     */
    @ManyToOne
    @JoinColumn(
            name = "origin_indicator_id",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_special_rate_value_origin_indicator")
    )
    private Indicator originIndicator;

    /**
     * Hours specification for the special rate.
     * Original field: VALORESPECIAL_HORAS
     */
    @Column(name = "hours_specification", length = 80)
    private String hoursSpecification;

    /**
     * Type of value.
     * Original field: VALORESPECIAL_TIPOVALOR
     */
    @Column(name = "value_type", nullable = false)
    @ColumnDefault("0")
    private Integer valueType;
}