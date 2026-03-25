package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.component.export.excel.ExcelGeneratorBuilder;
import com.infomedia.abacox.telephonypricing.constants.RefTable;
import com.infomedia.abacox.telephonypricing.db.entity.Inventory;
import com.infomedia.abacox.telephonypricing.db.repository.InventoryRepository;
import com.infomedia.abacox.telephonypricing.dto.inventory.CreateInventory;
import com.infomedia.abacox.telephonypricing.dto.inventory.UpdateInventory;
import com.infomedia.abacox.telephonypricing.service.common.CrudService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

@Service
public class InventoryService extends CrudService<Inventory, Long, InventoryRepository> {

    private final HistoryControlService historyControlService;

    public InventoryService(InventoryRepository repository, HistoryControlService historyControlService) {
        super(repository);
        this.historyControlService = historyControlService;
    }

    @Transactional
    public Inventory create(CreateInventory cDto) {
        Inventory inventory = Inventory.builder()
                .serialNumber(cDto.getSerialNumber())
                .mac(cDto.getMac())
                .plate(cDto.getPlate())
                .employeeId(cDto.getEmployeeId())
                .inventoryEquipmentId(cDto.getInventoryEquipmentId())
                .service(cDto.getService())
                .locationId(cDto.getLocationId())
                .subdivisionId(cDto.getSubdivisionId())
                .costCenterId(cDto.getCostCenterId())
                .networkUser(cDto.getNetworkUser())
                .equipmentTypeId(cDto.getEquipmentTypeId())
                .description(cDto.getDescription())
                .comments(cDto.getComments())
                .account(cDto.getAccount())
                .changeReason(cDto.getChangeReason())
                .inventorySupplierId(cDto.getInventorySupplierId())
                .installationDate(cDto.getInstallationDate())
                .inventoryWorkOrderTypeId(cDto.getInventoryWorkOrderTypeId())
                .inventoryUserTypeId(cDto.getInventoryUserTypeId())
                .caseActNumber(cDto.getCaseActNumber())
                .caseAct(cDto.getCaseAct())
                .inventoryOwnerId(cDto.getInventoryOwnerId())
                .inventoryAdditionalServiceId(cDto.getInventoryAdditionalServiceId())
                .permissionsExpiry(cDto.getPermissionsExpiry())
                .permissionsExtId(cDto.getPermissionsExtId())
                .build();

        historyControlService.initHistory(inventory);
        return save(inventory);
    }

    @Transactional
    public Inventory update(Long id, UpdateInventory uDto) {
        Inventory current = get(id);
        Inventory updated = current.toBuilder().build();

        uDto.getSerialNumber().ifPresent(updated::setSerialNumber);
        uDto.getMac().ifPresent(updated::setMac);
        uDto.getPlate().ifPresent(updated::setPlate);
        uDto.getEmployeeId().ifPresent(updated::setEmployeeId);
        uDto.getInventoryEquipmentId().ifPresent(updated::setInventoryEquipmentId);
        uDto.getService().ifPresent(updated::setService);
        uDto.getLocationId().ifPresent(updated::setLocationId);
        uDto.getSubdivisionId().ifPresent(updated::setSubdivisionId);
        uDto.getCostCenterId().ifPresent(updated::setCostCenterId);
        uDto.getNetworkUser().ifPresent(updated::setNetworkUser);
        uDto.getEquipmentTypeId().ifPresent(updated::setEquipmentTypeId);
        uDto.getDescription().ifPresent(updated::setDescription);
        uDto.getComments().ifPresent(updated::setComments);
        uDto.getAccount().ifPresent(updated::setAccount);
        uDto.getChangeReason().ifPresent(updated::setChangeReason);
        uDto.getInventorySupplierId().ifPresent(updated::setInventorySupplierId);
        uDto.getInstallationDate().ifPresent(updated::setInstallationDate);
        uDto.getInventoryWorkOrderTypeId().ifPresent(updated::setInventoryWorkOrderTypeId);
        uDto.getInventoryUserTypeId().ifPresent(updated::setInventoryUserTypeId);
        uDto.getCaseActNumber().ifPresent(updated::setCaseActNumber);
        uDto.getCaseAct().ifPresent(updated::setCaseAct);
        uDto.getInventoryOwnerId().ifPresent(updated::setInventoryOwnerId);
        uDto.getInventoryAdditionalServiceId().ifPresent(updated::setInventoryAdditionalServiceId);
        uDto.getPermissionsExpiry().ifPresent(updated::setPermissionsExpiry);
        uDto.getPermissionsExtId().ifPresent(updated::setPermissionsExtId);

        return historyControlService.processUpdate(
                current,
                updated,
                Map.of("SerialNumber", Inventory::getSerialNumber, "Employee", Inventory::getEmployeeId),
                RefTable.INVENTORY,
                getRepository());
    }

    @Transactional
    public void retire(Long id) {
        Inventory inventory = get(id);
        historyControlService.processRetire(inventory, RefTable.INVENTORY, getRepository());
    }

    public ByteArrayResource exportExcel(Specification<Inventory> specification, Pageable pageable, ExcelGeneratorBuilder builder) {
        Slice<Inventory> collection = findAsSlice(specification, pageable);
        try {
            InputStream inputStream = builder.withEntities(collection.toList()).generateAsInputStream();
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
