package com.infomedia.abacox.telephonypricing.db.entity;

import com.infomedia.abacox.telephonypricing.db.entity.superclass.AuditedEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

/**
 * Entity representing inventory work order types (OT = Orden de Trabajo).
 * Original table name: inveot
 */
@Entity
@Table(name = "inventory_work_order_type", indexes = {
        @Index(name = "idx_inventory_work_order_type_name", columnList = "name")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class InventoryWorkOrderType extends AuditedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "inventory_work_order_type_id_seq")
    @SequenceGenerator(name = "inventory_work_order_type_id_seq", sequenceName = "inventory_work_order_type_id_seq", allocationSize = 1, initialValue = 10000000)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "name", length = 50)
    private String name;
}
