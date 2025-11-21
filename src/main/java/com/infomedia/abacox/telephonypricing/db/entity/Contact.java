package com.infomedia.abacox.telephonypricing.db.entity;

import com.infomedia.abacox.telephonypricing.db.entity.superclass.ActivableEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnDefault;

@Entity
@Table(
    name = "contact",
    indexes = {
        // Critical for DialedNumberUsageReport (JOIN on phone number)
        @Index(name = "idx_contact_phone", columnList = "phone_number"),
        @Index(name = "idx_contact_employee", columnList = "employee_id"),
        @Index(name = "idx_contact_company", columnList = "company_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class Contact extends ActivableEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "contact_id_seq")
    @SequenceGenerator(name = "contact_id_seq", sequenceName = "contact_id_seq", allocationSize = 1, initialValue = 10000000)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "contact_type", nullable = false)
    @ColumnDefault("false")
    private Boolean contactType;

    @Column(name = "employee_id")
    private Long employeeId;
    
    @ManyToOne
    @JoinColumn(name = "employee_id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_contact_employee"))
    private Employee employee;

    @Column(name = "company_id")
    private Long companyId;
    
    @ManyToOne
    @JoinColumn(name = "company_id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_contact_company"))
    private Company company;

    @Column(name = "phone_number", length = 255, nullable = false)
    @ColumnDefault("''")
    private String phoneNumber;

    @Column(name = "name", length = 255, nullable = false)
    @ColumnDefault("''")
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "indicator_id")
    private Long indicatorId;
    
    @ManyToOne
    @JoinColumn(name = "indicator_id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_contact_indicator"))
    private Indicator indicator;
}