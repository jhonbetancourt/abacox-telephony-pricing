package com.infomedia.abacox.telephonypricing.entity;

import com.infomedia.abacox.telephonypricing.entity.superclass.AuditedEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnDefault;

/**
 * Entity representing telephone prefix configurations.
 * Original table name: PREFIJO
 */
@Entity
@Table(name = "prefix")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class Prefix extends AuditedEntity {

    /**
     * Primary key for the prefix.
     * Original field: PREFIJO_ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "prefix_id_seq")
    @SequenceGenerator(
            name = "prefix_id_seq",
            sequenceName = "prefix_id_seq",
            allocationSize = 1,
            initialValue = 1000000
    )
    @Column(name = "id", nullable = false)
    private Long id;

    /**
     * ID of the telecom operator.
     * Original field: PREFIJO_OPERADOR_ID
     */
    //TODO: This should be a foreign key to the Operator entity
    @Column(name = "operator_id", nullable = false)
    @ColumnDefault("0")
    private Long operatorId;

    /**
     * Type of telephone service.
     * Original field: PREFIJO_TIPOTELE_ID
     */
    //TODO: This should be a foreign key to the TelephoneType entity
    @Column(name = "telephone_type_id", nullable = false)
    @ColumnDefault("0")
    private Long telephoneTypeId;

    /**
     * The actual prefix code string.
     * Original field: PREFIJO_PREFIJO
     */
    @Column(name = "code", length = 10, nullable = false)
    @ColumnDefault("")
    private String code;

    /**
     * Base value for the prefix.
     * Original field: PREFIJO_VALORBASE
     */
    @Column(name = "base_value", nullable = false)
    @ColumnDefault("0")
    private BigDecimal baseValue;

    /**
     * Flag indicating if the band is OK.
     * Original field: PREFIJO_BANDAOK
     */
    @Column(name = "band_ok", nullable = false)
    @ColumnDefault("true")
    private boolean bandOk;

    /**
     * Flag indicating if VAT is included in the price.
     * Original field: PREFIJO_IVAINC
     */
    @Column(name = "vat_included", nullable = false)
    @ColumnDefault("false")
    private boolean vatIncluded;

    /**
     * VAT value for the prefix.
     * Original field: PREFIJO_IVA
     */
    @Column(name = "vat_value", nullable = false)
    @ColumnDefault("0")
    private BigDecimal vatValue;
}