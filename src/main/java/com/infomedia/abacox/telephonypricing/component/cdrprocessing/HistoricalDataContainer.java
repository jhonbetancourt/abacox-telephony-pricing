package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import com.infomedia.abacox.telephonypricing.db.entity.Employee;
import com.infomedia.abacox.telephonypricing.db.entity.ExtensionRange;
import lombok.Getter;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@Getter
public class HistoricalDataContainer {

    // Maps Extension/AuthCode -> Timeline
    private final Map<String, HistoricalTimeline<Employee>> employeeTimelinesByExtension = new HashMap<>();
    private final Map<String, HistoricalTimeline<Employee>> employeeTimelinesByAuthCode = new HashMap<>();

    // Set of extensions that HAVE a history group (even if no current slice
    // matches)
    // Used to block auto-creation (Step 6)
    private final Set<String> extensionsWithHistory;
    private final Set<String> authCodesWithHistory;

    // Ranges are also historical (Step 5)
    private final Map<Long, HistoricalTimeline<ExtensionRange>> rangeTimelines = new HashMap<>();

    public HistoricalDataContainer(Set<String> extensionsWithHistory, Set<String> authCodesWithHistory) {
        this.extensionsWithHistory = extensionsWithHistory;
        this.authCodesWithHistory = authCodesWithHistory;
    }

    public void addEmployeeTimelineByExtension(String extension, HistoricalTimeline<Employee> timeline) {
        employeeTimelinesByExtension.put(extension, timeline);
    }

    public void addEmployeeTimelineByAuthCode(String authCode, HistoricalTimeline<Employee> timeline) {
        employeeTimelinesByAuthCode.put(authCode, timeline);
    }

    public void addRangeTimeline(Long rangeId, HistoricalTimeline<ExtensionRange> timeline) {
        rangeTimelines.put(rangeId, timeline);
    }

    public boolean hasHistoryForExtension(String extension) {
        return extensionsWithHistory.contains(extension);
    }

    public boolean hasHistoryForAuthCode(String authCode) {
        return authCodesWithHistory.contains(authCode);
    }
}
