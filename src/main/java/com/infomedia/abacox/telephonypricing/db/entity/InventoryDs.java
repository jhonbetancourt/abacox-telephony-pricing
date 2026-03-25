package com.infomedia.abacox.telephonypricing.db.entity;

import com.infomedia.abacox.telephonypricing.db.entity.superclass.AuditedEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;


/**
 * Entity representing inventory suppliers/distributors.
 * Original table name: inveds
 */
@Entity
@Table(name = "inventory_supplier", indexes = {
        @Index(name = "idx_inventory_supplier_name", columnList = "name"),
        @Index(name = "idx_inventory_supplier_equipment", columnList = "inventory_equipment_id"),
        @Index(name = "idx_inventory_supplier_subdivision", columnList = "subdivision_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class InventoryDs extends AuditedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "inventory_supplier_id_seq")
    @SequenceGenerator(name = "inventory_supplier_id_seq", sequenceName = "inventory_supplier_id_seq", allocationSize = 1, initialValue = 10000000)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "name", length = 50)
    private String name;

    @Column(name = "inventory_equipment_id")
    private Long inventoryEquipmentId;

    @ManyToOne
    @JoinColumn(name = "inventory_equipment_id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_inventory_supplier_equipment"))
    private InventoryEquipment inventoryEquipment;

    @Column(name = "company", length = 50)
    private String company;

    @Column(name = "nit", length = 15)
    private String nit;

    @Column(name = "subdivision_id")
    private Long subdivisionId;

    @ManyToOne
    @JoinColumn(name = "subdivision_id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_inventory_supplier_subdivision"))
    private Subdivision subdivision;

    @Column(name = "address", length = 100)
    private String address;

    @Builder.Default
    @Enumerated(EnumType.ORDINAL)
    @Column(name = "status", nullable = false)
    private DsStatus status = DsStatus.ASSIGNED;

    public enum DsStatus {
        ASSIGNED,      // 0 - ASIGNADO
        AVAILABLE,     // 1 - DISPONIBLE
        DECOMMISSIONED // 2 - BAJA
    }
}
