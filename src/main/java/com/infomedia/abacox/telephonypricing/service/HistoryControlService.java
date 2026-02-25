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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
        entity.setHistoryChange("Initial Creation");
    }

    @Transactional
    public <T extends HistoricalEntity> T processUpdate(
            T current,
            T updated,
            Map<String, Function<T, Object>> triggerFieldAccessors,
            RefTable refTable,
            JpaRepository<T, Long> repository) {

        List<String> changedFields = new ArrayList<>();
        for (var entry : triggerFieldAccessors.entrySet()) {
            if (!Objects.equals(entry.getValue().apply(current), entry.getValue().apply(updated))) {
                changedFields.add(entry.getKey());
            }
        }

        if (!changedFields.isEmpty()) {
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

            // Build a descriptive reason
            String reason = "Change in: " + String.join(", ", changedFields);
            updated.setHistoryChange(reason);

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
    public <T extends HistoricalEntity> void processRetire(T entity, RefTable refTable,
            JpaRepository<T, Long> repository) {
        HistoryControl hc;
        if (entity.getHistoryControlId() == null) {
            log.info("Retiring record {}/{} that has no history group. Creating group first.", refTable,
                    entity.getId());
            hc = historyControlRepository.save(HistoryControl.builder()
                    .refTable(refTable.getId())
                    .refId(entity.getId())
                    .historySince(entity.getHistorySince())
                    .build());

            entity.setHistoryControlId(hc.getId());
            repository.save(entity);
        } else {
            hc = historyControlRepository.findById(entity.getHistoryControlId())
                    .orElseThrow(() -> new RuntimeException(
                            "HistoryControl not found for ID " + entity.getHistoryControlId()));
        }

        log.info("Retiring history group {} for entity {}/{}", hc.getId(), refTable, entity.getId());

        // Populate the history change in the record being retired
        entity.setHistoryChange("Retirement / Deactivation");
        repository.save(entity);

        // Logic for deactivation in historical system: negate the ref_id
        if (hc.getRefId() != null) {
            hc.setRefId(-Math.abs(hc.getRefId()));
        }
        historyControlRepository.save(hc);
    }
}
