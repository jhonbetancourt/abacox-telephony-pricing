package com.infomedia.abacox.telephonypricing.entity;

import com.infomedia.abacox.telephonypricing.entity.superclass.ActivableEntity;
import com.infomedia.abacox.telephonypricing.entity.superclass.AuditedEntity;
import jakarta.persistence.*;
import lombok.*;

import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnDefault;

/**
 * Entity representing a directory entry/contact.
 * Original table name: DIRECTORIO
 */
@Entity
@Table(name = "contact")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class Contact extends ActivableEntity {

    /**
     * Primary key for the contact.
     * Original field: DIRECTORIO_ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "contact_id_seq")
    @SequenceGenerator(
            name = "contact_id_seq",
            sequenceName = "contact_id_seq",
            allocationSize = 1,
            initialValue = 10000000
    )
    @Column(name = "id", nullable = false)
    private Long id;

    /**
     * Type of contact (0/1 flag).
     * Original field: DIRECTORIO_TIPO
     */
    @Column(name = "contact_type", nullable = false)
    @ColumnDefault("false")
    private Boolean contactType;

    /**
     * ID of the associated employee.
     * Original field: DIRECTORIO_FUNCIONARIO_ID
     */
    @Column(name = "employee_id")
    private Long employeeId;
    
    /**
     * Employee relationship.
     */
    @ManyToOne
    @JoinColumn(
        name = "employee_id", 
        insertable = false, 
        updatable = false,
        foreignKey = @ForeignKey(name = "fk_contact_employee")
    )
    private Employee employee;

    /**
     * ID of the associated company.
     * Original field: DIRECTORIO_EMPRESA_ID
     */
    @Column(name = "company_id")
    private Long companyId;
    
    /**
     * Company relationship.
     */
    @ManyToOne
    @JoinColumn(
        name = "company_id", 
        insertable = false, 
        updatable = false,
        foreignKey = @ForeignKey(name = "fk_contact_company")
    )
    private Company company;

    /**
     * Phone number.
     * Original field: DIRECTORIO_TELEFONO
     */
    @Column(name = "phone_number", length = 255, nullable = false)
    @ColumnDefault("")
    private String phoneNumber;

    /**
     * Contact name.
     * Original field: DIRECTORIO_NOMBRE
     */
    @Column(name = "name", length = 255, nullable = false)
    @ColumnDefault("")
    private String name;

    /**
     * Description or notes about the contact.
     * Original field: DIRECTORIO_DESCRIPCION
     */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /**
     * ID of the area code/indicator.
     * Original field: DIRECTORIO_INDICATIVO_ID
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
        foreignKey = @ForeignKey(name = "fk_contact_indicator")
    )
    private Indicator indicator;

    // The following field was commented out in the original schema:
    
    /**
     * Email address (commented out in original schema).
     * Original field: DIRECTORIO_EMAIL
     */
    // @Column(name = "email", length = 50, nullable = false)
    // @ColumnDefault("''")
    // private String email;
}