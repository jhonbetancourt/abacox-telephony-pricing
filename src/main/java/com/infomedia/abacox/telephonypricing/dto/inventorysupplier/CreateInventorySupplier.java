package com.infomedia.abacox.telephonypricing.dto.inventorysupplier;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * DTO for creating {@link com.infomedia.abacox.telephonypricing.db.entity.InventorySupplier}
 */
@EqualsAndHashCode(callSuper = false)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CreateInventorySupplier {
    @Size(max = 50)
    private String name;
    private Long inventoryEquipmentId;
    @Size(max = 50)
    private String company;
    @Size(max = 15)
    private String nit;
    private Long subdivisionId;
    @Size(max = 100)
    private String address;
}
