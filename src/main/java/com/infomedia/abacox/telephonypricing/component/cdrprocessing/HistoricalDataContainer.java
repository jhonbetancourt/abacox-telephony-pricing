// File: com/infomedia/abacox/telephonypricing/component/cdrprocessing/HistoricalDataContainer.java
package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import com.infomedia.abacox.telephonypricing.db.entity.Employee;
import com.infomedia.abacox.telephonypricing.db.entity.ExtensionRange;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;

import java.util.*;

@Getter
@Log4j2
public class HistoricalDataContainer {

    private final Map<String, ResolvedTimeline> extensionTimelines = new HashMap<>();
    private final Map<String, ResolvedTimeline> authCodeTimelines = new HashMap<>();
    private final Map<Long, List<RangeSlice>> rangeSlicesByCommId = new HashMap<>();

    public void addEmployeeExtensionSlice(String extension, Employee emp, long fdesde, long fhasta, boolean isGlobal) {
        extensionTimelines.computeIfAbsent(extension, k -> new ResolvedTimeline()).addSlice(emp, fdesde, fhasta, isGlobal);
    }

    public void addEmployeeAuthCodeSlice(String authCode, Employee emp, long fdesde, long fhasta, boolean isGlobal) {
        authCodeTimelines.computeIfAbsent(authCode, k -> new ResolvedTimeline()).addSlice(emp, fdesde, fhasta, isGlobal);
    }

    public void addRangeSlice(Long commLocationId, ExtensionRange range, long fdesde, long fhasta) {
        rangeSlicesByCommId.computeIfAbsent(commLocationId, k -> new ArrayList<>()).add(new RangeSlice(range, fdesde, fhasta));
    }

    @Data
    @AllArgsConstructor
    public static class EmployeeSlice {
        private Employee employee;
        private long fdesde;
        private long fhasta;
    }

    @Data
    @AllArgsConstructor
    public static class RangeSlice {
        private ExtensionRange range;
        private long fdesde;
        private long fhasta;
    }

    public static class ResolvedTimeline {
        private final Map<String, EmployeeSlice> slices = new HashMap<>();

        /**
         * Matches PHP's AsignarHistoricosFuncionarios logic to resolve overlapping 
         * timelines for the same identifier across different history control groups.
         */
        public void addSlice(Employee emp, long fdesde, long fhasta, boolean isGlobal) {
            String llave = isGlobal ? "" : String.valueOf(emp.getCommunicationLocationId());
            long currentFdesde = fdesde;
            EmployeeSlice currentSlice = new EmployeeSlice(emp, currentFdesde, fhasta);

            int maxInteractions = 200;
            while (slices.containsKey(currentFdesde + llave) && maxInteractions > 0) {
                maxInteractions--;
                EmployeeSlice existing = slices.get(currentFdesde + llave);
                long fhastaUps = existing.getFhasta();
                long currentFhasta = currentSlice.getFhasta();

                if (fhastaUps > 0 && fhastaUps < currentFhasta) {
                    // Existing ends before new ends: push new one's start date
                    currentFdesde = fhastaUps + 1;
                    currentSlice.setFdesde(currentFdesde);
                } else if ((fhastaUps <= 0 && currentFhasta > 0) || fhastaUps > currentFhasta) {
                    // Existing end date is open or > new end date: Swap them
                    EmployeeSlice temp = existing;
                    slices.put(currentFdesde + llave, currentSlice);
                    currentSlice = temp;
                    currentFdesde = currentFhasta + 1;
                    currentSlice.setFdesde(currentFdesde);
                } else {
                    // Exact same start and end? Differentiate by plant if not global
                    if (!llave.equals(String.valueOf(currentSlice.getEmployee().getCommunicationLocationId()))) {
                        llave = String.valueOf(currentSlice.getEmployee().getCommunicationLocationId());
                    } else {
                        break;
                    }
                }
            }
            if (maxInteractions <= 0) {
                log.error("Historical timeline collision resolution maxed out for Employee ID {}! Possible dirty overlapping data.", emp.getId());
            }
            slices.put(currentFdesde + llave, currentSlice);
        }

        public Optional<Employee> findMatch(long callTimestampEpoch, Long commLocationIdContext) {
            List<EmployeeSlice> sortedSlices = new ArrayList<>(slices.values());
            // Sort by fdesde DESC to find the most recent valid timeline
            sortedSlices.sort((a, b) -> Long.compare(b.getFdesde(), a.getFdesde()));

            Employee bestMatch = null;
            Employee fallbackGlobalMatch = null;

            for (EmployeeSlice slice : sortedSlices) {
                if ((slice.getFdesde() <= 0 || slice.getFdesde() <= callTimestampEpoch) &&
                    (slice.getFhasta() <= 0 || slice.getFhasta() >= callTimestampEpoch)) {

                    Long sliceCommId = slice.getEmployee().getCommunicationLocationId();

                    if (commLocationIdContext == null || Objects.equals(sliceCommId, commLocationIdContext)) {
                        bestMatch = slice.getEmployee();
                        break;
                    } else if (fallbackGlobalMatch == null) {
                        fallbackGlobalMatch = slice.getEmployee();
                    }
                }
            }

            if (bestMatch != null) return Optional.of(bestMatch);
            return Optional.ofNullable(fallbackGlobalMatch);
        }
    }
}