package com.infomedia.abacox.telephonypricing.dto.inventorysupplier;

import com.infomedia.abacox.telephonypricing.dto.inventoryequipment.InventoryEquipmentDto;
import com.infomedia.abacox.telephonypricing.dto.subdivision.SubdivisionDto;
import com.infomedia.abacox.telephonypricing.dto.superclass.ActivableDto;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * DTO for {@link com.infomedia.abacox.telephonypricing.db.entity.InventorySupplier}
 */
@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class InventorySupplierDto extends ActivableDto {
    private Long id;
    private String name;
    private Long inventoryEquipmentId;
    private String company;
    private String nit;
    private Long subdivisionId;
    private String address;
    private InventoryEquipmentDto inventoryEquipment;
    private SubdivisionDto subdivision;
}
