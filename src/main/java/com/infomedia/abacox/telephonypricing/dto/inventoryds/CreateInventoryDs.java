package com.infomedia.abacox.telephonypricing.dto.inventoryds;

import com.infomedia.abacox.telephonypricing.db.entity.InventoryDs.DsStatus;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * DTO for creating {@link com.infomedia.abacox.telephonypricing.db.entity.InventoryDs}
 */
@EqualsAndHashCode(callSuper = false)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CreateInventoryDs {
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
    private DsStatus status;
}
