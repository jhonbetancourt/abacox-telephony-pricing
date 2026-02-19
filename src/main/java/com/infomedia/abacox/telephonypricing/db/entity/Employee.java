// src/main/java/com/infomedia/abacox/telephonypricing/db/entity/Employee.java
package com.infomedia.abacox.telephonypricing.db.entity;

import com.infomedia.abacox.telephonypricing.db.entity.superclass.ActivableEntity;
import jakarta.persistence.*;
import lombok.*;

import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnDefault;

@Entity
@Table(name = "employee", indexes = {
                // Critical for CDR Processing (EmployeeLookupService) and ExtensionGroupReport
                @Index(name = "idx_employee_ext_active", columnList = "extension, active"),

                // Critical for Auth Code assignment in CDR Processing
                @Index(name = "idx_employee_auth_active", columnList = "auth_code, active"),

                // Optimizes reporting joins
                @Index(name = "idx_employee_subdivision", columnList = "subdivision_id"),
                @Index(name = "idx_employee_cost_center", columnList = "cost_center_id"),
                @Index(name = "idx_employee_comm_loc", columnList = "communication_location_id"),

                // Optimizes text search in filters (e.g., 'ILIKE %name%')
                // Note: Standard indexes help exact matches; standard btree has limited use for
                // leading wildcard ILIKE
                // but is still good practice for sorting/grouping.
                @Index(name = "idx_employee_name", columnList = "name")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class Employee extends ActivableEntity {

        @Id
        @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "employee_id_seq")
        @SequenceGenerator(name = "employee_id_seq", sequenceName = "employee_id_seq", allocationSize = 1, initialValue = 10000000)
        @Column(name = "id", nullable = false)
        private Long id;

        @Column(name = "name", length = 255, nullable = false)
        @ColumnDefault("''")
        private String name;

        @Column(name = "subdivision_id")
        private Long subdivisionId;

        @ManyToOne
        @JoinColumn(name = "subdivision_id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_employee_subdivision"))
        private Subdivision subdivision;

        @Column(name = "cost_center_id")
        private Long costCenterId;

        @ManyToOne
        @JoinColumn(name = "cost_center_id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_employee_cost_center"))
        private CostCenter costCenter;

        @Column(name = "auth_code", length = 50, nullable = false)
        @ColumnDefault("''")
        private String authCode;

        @Column(name = "extension", length = 50, nullable = false)
        @ColumnDefault("''")
        private String extension;

        @Column(name = "communication_location_id")
        private Long communicationLocationId;

        @ManyToOne
        @JoinColumn(name = "communication_location_id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_employee_communication_location"))
        private CommunicationLocation communicationLocation;

        @Column(name = "job_position_id")
        private Long jobPositionId;

        @ManyToOne
        @JoinColumn(name = "job_position_id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_employee_job_position"))
        private JobPosition jobPosition;

        @Column(name = "email", length = 100, nullable = false)
        @ColumnDefault("''")
        private String email;

        @Column(name = "phone", length = 100, nullable = false)
        @ColumnDefault("''")
        private String phone;

        @Column(name = "address", length = 255, nullable = false)
        @ColumnDefault("''")
        private String address;

        @Column(name = "id_number", length = 20, nullable = false)
        @ColumnDefault("''")
        private String idNumber;
}