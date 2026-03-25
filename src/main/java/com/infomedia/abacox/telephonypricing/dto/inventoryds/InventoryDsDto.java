package com.infomedia.abacox.telephonypricing.dto.inventoryds;

import com.infomedia.abacox.telephonypricing.db.entity.InventoryDs.DsStatus;
import com.infomedia.abacox.telephonypricing.dto.inventoryequipment.InventoryEquipmentDto;
import com.infomedia.abacox.telephonypricing.dto.subdivision.SubdivisionDto;
import com.infomedia.abacox.telephonypricing.dto.superclass.ActivableDto;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * DTO for {@link com.infomedia.abacox.telephonypricing.db.entity.InventoryDs}
 */
@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class InventoryDsDto extends ActivableDto {
    private Long id;
    private String name;
    private Long inventoryEquipmentId;
    private String company;
    private String nit;
    private Long subdivisionId;
    private String address;
    private InventoryEquipmentDto inventoryEquipment;
    private SubdivisionDto subdivision;
    private DsStatus status;
}
