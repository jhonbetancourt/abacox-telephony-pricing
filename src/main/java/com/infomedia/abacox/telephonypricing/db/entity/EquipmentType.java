package com.infomedia.abacox.telephonypricing.db.entity;

import com.infomedia.abacox.telephonypricing.db.entity.superclass.AuditedEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

/**
 * Entity representing equipment types.
 * Original table name: tipoequipos
 */
@Entity
@Table(name = "equipment_type", indexes = {
        @Index(name = "idx_equipment_type_name", columnList = "name")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class EquipmentType extends AuditedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "equipment_type_id_seq")
    @SequenceGenerator(name = "equipment_type_id_seq", sequenceName = "equipment_type_id_seq", allocationSize = 1, initialValue = 10000000)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "name", length = 50)
    private String name;
}
