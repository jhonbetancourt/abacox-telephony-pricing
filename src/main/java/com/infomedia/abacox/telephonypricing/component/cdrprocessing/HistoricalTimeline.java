package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import com.infomedia.abacox.telephonypricing.db.entity.HistoricalEntity;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Log4j2
public class HistoricalTimeline<T extends HistoricalEntity> {

    @Getter
    private final Long historyControlId;
    private final List<HistoricalSlice<T>> slices = new ArrayList<>();

    public HistoricalTimeline(Long historyControlId, List<T> entities) {
        this.historyControlId = historyControlId;
        buildSlices(entities);
    }

    private void buildSlices(List<T> entities) {
        if (entities == null || entities.isEmpty())
            return;

        // Sort by historySince DESC (newest to oldest) as in the legacy PHP logic Step
        // 1
        List<T> sorted = new ArrayList<>(entities);
        sorted.sort(Comparator.comparing(HistoricalEntity::getHistorySince).reversed());

        LocalDateTime nextStart = null;

        for (T entity : sorted) {
            LocalDateTime start = entity.getHistorySince();
            LocalDateTime end = null;

            if (nextStart != null) {
                // Step 2: Dynamically Calculating Expiration Dates (fhasta)
                // expiration date of an older record to one second (or in this case, a tiny
                // fraction) before the newer record begins
                end = nextStart.minusNanos(1);
            }

            // Step 3: Resolving Timeline Collisions in Memory
            // If we find an overlap where the start is after the end we just calculated
            // (meaning they overlap in 'since' dates)
            // we'd follow the legacy logic to force linearity.
            // However, with historySince purely ascending, and the sorting desc,
            // the end of version N is version N+1's start - 1.

            slices.add(new HistoricalSlice<>(start, end, entity));
            nextStart = start;
        }
    }

    /**
     * Step 4: Evaluating the Individual Call (Assigning the Owner)
     */
    public Optional<T> findMatch(LocalDateTime timestamp) {
        if (timestamp == null)
            return Optional.empty();

        for (HistoricalSlice<T> slice : slices) {
            if (slice.isValidAt(timestamp)) {
                return Optional.of(slice.getEntity());
            }
        }
        return Optional.empty();
    }

    public boolean isEmpty() {
        return slices.isEmpty();
    }

    @Getter
    private static class HistoricalSlice<T extends HistoricalEntity> {
        private final LocalDateTime start;
        private final LocalDateTime end;
        private final T entity;

        public HistoricalSlice(LocalDateTime start, LocalDateTime end, T entity) {
            this.start = start;
            this.end = end;
            this.entity = entity;
        }

        public boolean isValidAt(LocalDateTime timestamp) {
            // Is the Call Timestamp >= Start Date AND (End date is null OR Call Timestamp
            // <= End date)?
            return !timestamp.isBefore(start) && (end == null || !timestamp.isAfter(end));
        }
    }
}
