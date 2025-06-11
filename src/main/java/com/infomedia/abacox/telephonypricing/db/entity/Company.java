package com.infomedia.abacox.telephonypricing.db.entity;

import com.infomedia.abacox.telephonypricing.db.entity.superclass.ActivableEntity;
import jakarta.persistence.*;
import lombok.*;

import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnDefault;

/**
 * Entity representing companies/businesses.
 * Original table name: EMPRESA
 */
@Entity
@Table(name = "company")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class Company extends ActivableEntity {

    /**
     * Primary key for the company.
     * Original field: EMPRESA_ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "company_id_seq")
    @SequenceGenerator(
            name = "company_id_seq",
            sequenceName = "company_id_seq",
            allocationSize = 1,
            initialValue = 10000000
    )
    @Column(name = "id", nullable = false)
    private Long id;

    /**
     * Additional information about the company.
     * Original field: EMPRESA_ADICIONAL
     */
    @Column(name = "additional_info", columnDefinition = "TEXT")
    private String additionalInfo;

    /**
     * Physical address of the company.
     * Original field: EMPRESA_DIRECCION
     */
    @Column(name = "address", length = 255, nullable = false)
    @ColumnDefault("")
    private String address;

    /**
     * Company name.
     * Original field: EMPRESA_EMPRESA
     */
    @Column(name = "name", length = 100, nullable = false)
    @ColumnDefault("")
    private String name;

    /**
     * Tax identification number.
     * Original field: EMPRESA_NIT
     */
    @Column(name = "tax_id", length = 100, nullable = false)
    @ColumnDefault("")
    private String taxId;

    /**
     * Legal business name.
     * Original field: EMPRESA_RSOCIAL
     */
    @Column(name = "legal_name", columnDefinition = "TEXT")
    private String legalName;

    /**
     * Website URL.
     * Original field: EMPRESA_URL
     */
    @Column(name = "website", length = 100, nullable = false)
    @ColumnDefault("")
    private String website;

    /**
     * ID of the area code/indicator.
     * Original field: EMPRESA_INDICATIVO_ID
     */
    @Column(name = "indicator_id")
    private Integer indicatorId;
    
    /**
     * Indicator relationship.
     */
    @ManyToOne
    @JoinColumn(
        name = "indicator_id", 
        insertable = false, 
        updatable = false,
        foreignKey = @ForeignKey(name = "fk_company_indicator")
    )
    private Indicator indicator;

    // The following field was commented out in the original schema:
    
    /**
     * Alternative company ID (commented out in original schema).
     * Original field: EMPRESA_IDEMPRESA
     */
    // @Column(name = "alternate_company_id", nullable = false)
    // @ColumnDefault("0")
    // private Integer alternateCompanyId;
}