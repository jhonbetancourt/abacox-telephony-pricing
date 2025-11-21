// src/main/java/com/infomedia/abacox/telephonypricing/db/entity/Prefix.java
package com.infomedia.abacox.telephonypricing.db.entity;

import com.infomedia.abacox.telephonypricing.db.entity.superclass.ActivableEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnDefault;

@Entity
@Table(
    name = "prefix",
    indexes = {
        // Critical for PrefixLookupService::findMatchingPrefixes and Internal lookups
        @Index(name = "idx_prefix_lookup", columnList = "telephony_type_id, operator_id, active"),
        
        // Optimizes string matching (though startsWith queries usually need code to be the first col in composite or standalone)
        @Index(name = "idx_prefix_code", columnList = "code")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class Prefix extends ActivableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "prefix_id_seq")
    @SequenceGenerator(
            name = "prefix_id_seq",
            sequenceName = "prefix_id_seq",
            allocationSize = 1,
            initialValue = 10000000
    )
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "operator_id")
    private Long operatorId;

    @ManyToOne
    @JoinColumn(
            name = "operator_id", 
            insertable = false, 
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_prefix_operator")
    )
    private Operator operator;

    @Column(name = "telephony_type_id")
    private Long telephonyTypeId;

    @ManyToOne
    @JoinColumn(
            name = "telephony_type_id",
            insertable = false, 
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_prefix_telephony_type")
    )
    private TelephonyType telephonyType;

    @Column(name = "code", length = 10, nullable = false)
    @ColumnDefault("''")
    private String code;

    @Column(name = "base_value", nullable = false)
    @ColumnDefault("0")
    private BigDecimal baseValue;

    @Column(name = "band_ok", nullable = false)
    @ColumnDefault("true")
    private boolean bandOk;

    @Column(name = "vat_included", nullable = false)
    @ColumnDefault("false")
    private boolean vatIncluded;

    @Column(name = "vat_value", nullable = false)
    @ColumnDefault("0")
    private BigDecimal vatValue;
}