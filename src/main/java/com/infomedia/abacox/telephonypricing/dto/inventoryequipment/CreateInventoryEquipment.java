package com.infomedia.abacox.telephonypricing.dto.inventoryequipment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import java.math.BigDecimal;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * DTO for creating {@link com.infomedia.abacox.telephonypricing.db.entity.InventoryEquipment}
 */
@EqualsAndHashCode(callSuper = false)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CreateInventoryEquipment {
    @NotBlank
    @Size(max = 100)
    private String name;
    private Long parentEquipmentId;
    private BigDecimal valueTt;
    private BigDecimal valueInfomedia;
}
