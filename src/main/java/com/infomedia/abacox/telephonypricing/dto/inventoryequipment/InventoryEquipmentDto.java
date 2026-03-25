package com.infomedia.abacox.telephonypricing.dto.inventoryequipment;

import com.infomedia.abacox.telephonypricing.dto.superclass.ActivableDto;
import lombok.AllArgsConstructor;
import lombok.Data;
import java.math.BigDecimal;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * DTO for {@link com.infomedia.abacox.telephonypricing.db.entity.InventoryEquipment}
 */
@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class InventoryEquipmentDto extends ActivableDto {
    private Long id;
    private String name;
    private Long parentEquipmentId;
    private BigDecimal valueTt;
    private BigDecimal valueInfomedia;
    private InventoryEquipmentDto parentEquipment;
}
