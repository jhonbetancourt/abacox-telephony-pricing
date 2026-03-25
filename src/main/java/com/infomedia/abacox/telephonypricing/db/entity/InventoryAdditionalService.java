package com.infomedia.abacox.telephonypricing.db.entity;

import com.infomedia.abacox.telephonypricing.db.entity.superclass.AuditedEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

/**
 * Entity representing inventory additional services.
 * Original table name: inveserviciosadc
 */
@Entity
@Table(name = "inventory_additional_service", indexes = {
        @Index(name = "idx_inventory_additional_service_name", columnList = "name")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class InventoryAdditionalService extends AuditedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "inventory_additional_service_id_seq")
    @SequenceGenerator(name = "inventory_additional_service_id_seq", sequenceName = "inventory_additional_service_id_seq", allocationSize = 1, initialValue = 10000000)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "name", length = 50)
    private String name;
}
