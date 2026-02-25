package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.constants.RefTable;
import com.infomedia.abacox.telephonypricing.db.entity.HistoricalEntity;
import com.infomedia.abacox.telephonypricing.db.entity.HistoryControl;
import com.infomedia.abacox.telephonypricing.db.repository.HistoryControlRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

@Service
@RequiredArgsConstructor
@Log4j2
public class HistoryControlService {

    private final HistoryControlRepository historyControlRepository;

    @Transactional
    public void initHistory(HistoricalEntity entity) {
        entity.setHistorySince(LocalDateTime.now());
        entity.setHistoryControlId(null);
    }

    /**
     * Processes an update to a historical entity.
     * 
     * @param current               The current state of the entity (loaded from
     *                              DB).
     *                              Note: This entity should be considered
     *                              "stale/read-only" if a trigger changes.
     * @param updated               The entity with NEW values applied.
     * @param triggerFieldAccessors List of functions to extract values of fields
     *                              that trigger a new version.
     * @param refTable              The identifier for the table group.
     * @param repository            The repository for saving the entity versions.
     * @return The saved active version of the entity.
     */
    @Transactional
    public <T extends HistoricalEntity> T processUpdate(
            T current,
            T updated,
            List<Function<T, Object>> triggerFieldAccessors,
            RefTable refTable,
            JpaRepository<T, Long> repository) {

        boolean triggerChanged = false;
        for (var accessor : triggerFieldAccessors) {
            if (!Objects.equals(accessor.apply(current), accessor.apply(updated))) {
                triggerChanged = true;
                break;
            }
        }

        if (triggerChanged) {
            HistoryControl hc;
            if (current.getHistoryControlId() == null) {
                log.info("Creating first historical control for {} ID {}", refTable, current.getId());
                hc = historyControlRepository.save(HistoryControl.builder()
                        .refTable(refTable.getId())
                        .refId(current.getId())
                        .historySince(current.getHistorySince())
                        .build());

                // Link the old version to the new group
                current.setHistoryControlId(hc.getId());
                repository.save(current);
            } else {
                hc = historyControlRepository.findById(current.getHistoryControlId())
                        .orElseThrow(() -> new RuntimeException(
                                "HistoryControl not found for ID " + current.getHistoryControlId()));
            }

            log.info("Trigger field changed for {} {}. Creating new historical version.", refTable, current.getId());

            // Create a new row by resetting the ID
            updated.setId(null);
            updated.setHistoryControlId(hc.getId());
            updated.setHistorySince(LocalDateTime.now());

            // Save the new version
            T savedNewVersion = repository.save(updated);

            // Update the history control pointer to the new winner
            hc.setRefId(savedNewVersion.getId());
            historyControlRepository.save(hc);

            return savedNewVersion;
        } else {
            // Minor change, just save as an update to the current record
            return repository.save(updated);
        }
    }

    @Transactional
    public <T extends HistoricalEntity> void retire(RefTable refTable, T entity, JpaRepository<T, Long> repository) {
        Long historyControlId = entity.getHistoryControlId();
        HistoryControl hc;

        if (historyControlId == null) {
            log.info("Retiring {} {} that has no history group. Creating group first.", refTable, entity.getId());
            hc = historyControlRepository.save(HistoryControl.builder()
                    .refTable(refTable.getId())
                    .refId(entity.getId())
                    .historySince(entity.getHistorySince())
                    .build());

            entity.setHistoryControlId(hc.getId());
            repository.save(entity);
        } else {
            hc = historyControlRepository.findById(historyControlId)
                    .orElseThrow(() -> new RuntimeException("HistoryControl not found for ID " + historyControlId));
        }

        if (!Objects.equals(hc.getRefTable(), refTable.getId())) {
            throw new IllegalArgumentException(
                    "HistoryControl " + hc.getId() + " does not belong to table " + refTable);
        }

        log.info("Retiring history group {} for table {}", hc.getId(), refTable);
        hc.setRefId(null);
        historyControlRepository.save(hc);
    }
}
