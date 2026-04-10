package com.infomedia.abacox.telephonypricing.service.report;

import com.infomedia.abacox.telephonypricing.component.export.excel.ExcelGeneratorBuilder;
import com.infomedia.abacox.telephonypricing.component.modeltools.ModelConverter;
import com.infomedia.abacox.telephonypricing.component.utils.SortingUtils;
import com.infomedia.abacox.telephonypricing.db.entity.ExtensionList;
import com.infomedia.abacox.telephonypricing.db.repository.ExtensionListRepository;
import com.infomedia.abacox.telephonypricing.db.repository.ReportRepository;
import com.infomedia.abacox.telephonypricing.dto.report.ExtensionGroupDto;
import com.infomedia.abacox.telephonypricing.dto.report.ExtensionGroupReportDto;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Service
public class ExtensionGroupReportService {

    private final ReportRepository reportRepository;
    private final ExtensionListRepository extensionListRepository;
    private final ModelConverter modelConverter;

    /**
     * Generates the "Llamadas Grupo de Extensiones" report (legacy
     * grupo_valores.php).
     *
     * Returns a slice of ExtensionGroupDto (each with groupName + items list).
     */
    @Transactional(readOnly = true)
    public Slice<ExtensionGroupDto> generateExtensionGroupReport(
            LocalDateTime startDate,
            LocalDateTime endDate,
            Long groupId,
            String voicemailNumber,
            List<Long> operatorIds,
            Pageable pageable) {

        // 1. Fetch active extension groups (optionally filtered by ID)
        List<ExtensionList> groups = extensionListRepository.findActiveByTypeAndOptionalId("EXT", groupId);

        if (groups.isEmpty()) {
            return new SliceImpl<>(Collections.emptyList(), pageable, false);
        }

        // 2. Build extension → groupName mapping (preserving group declaration order)
        Map<String, String> extensionToGroupName = new LinkedHashMap<>();
        for (ExtensionList group : groups) {
            String listing = group.getExtensionList();
            if (listing == null || listing.isBlank())
                continue;
            String[] exts = listing.split("[,\n]");
            for (String ext : exts) {
                String trimmed = ext.trim();
                if (!trimmed.isEmpty()) {
                    extensionToGroupName.putIfAbsent(trimmed, group.getName());
                }
            }
        }

        List<String> allExtensions = new ArrayList<>(extensionToGroupName.keySet());

        if (allExtensions.isEmpty()) {
            return new SliceImpl<>(Collections.emptyList(), pageable, false);
        }

        // 3. Run the SQL report for all extensions (unpaged for grouping)
        List<ExtensionGroupReportDto> allRows = reportRepository
                .getExtensionGroupReport(startDate, endDate, allExtensions,
                        voicemailNumber != null ? voicemailNumber : "",
                        operatorIds != null ? operatorIds : Collections.emptyList(),
                        Pageable.unpaged())
                .getContent()
                .stream()
                .map(row -> modelConverter.map(row, ExtensionGroupReportDto.class))
                .collect(Collectors.toList());

        // 4. Group rows by group name (preserving group declaration order)
        List<String> groupOrder = groups.stream().map(ExtensionList::getName).collect(Collectors.toList());

        Map<String, List<ExtensionGroupReportDto>> grouped = new LinkedHashMap<>();
        for (String gName : groupOrder) {
            grouped.put(gName, new ArrayList<>());
        }

        for (ExtensionGroupReportDto row : allRows) {
            String gName = extensionToGroupName.getOrDefault(row.getExtension(), "");
            grouped.computeIfAbsent(gName, k -> new ArrayList<>()).add(row);
        }

        // 5. Build ExtensionGroupDto list (each group has name + items)
        List<ExtensionGroupDto> groupDtos = new ArrayList<>();
        for (Map.Entry<String, List<ExtensionGroupReportDto>> entry : grouped.entrySet()) {
            if (entry.getValue().isEmpty())
                continue;
            groupDtos.add(ExtensionGroupDto.builder()
                    .groupName(entry.getKey())
                    .items(entry.getValue())
                    .build());
        }

        SortingUtils.sort(groupDtos, pageable.getSort(), Sort.by("groupName"));

        // 6. Paginate the group list using SliceImpl
        int start = (int) pageable.getOffset();
        int end = Math.min((start + pageable.getPageSize()), groupDtos.size());
        List<ExtensionGroupDto> pageContent = start > groupDtos.size()
                ? Collections.emptyList()
                : groupDtos.subList(start, end);

        boolean hasNext = end < groupDtos.size();
        return new SliceImpl<>(pageContent, pageable, hasNext);
    }

    public void exportExcelExtensionGroupReport(
            LocalDateTime startDate, LocalDateTime endDate,
            Long groupId, String voicemailNumber, List<Long> operatorIds,
            OutputStream outputStream, ExcelGeneratorBuilder builder) {
        List<ExtensionGroupDto> allGroups = generateExtensionGroupReport(
                startDate, endDate, groupId, voicemailNumber, operatorIds, Pageable.unpaged())
                .getContent();
        try {
            builder.withFlattenedCollection("items")
                    .withIncludedFields(
                            "groupName",
                            "items.employeeId", "items.employeeName", "items.extension",
                            "items.subdivisionName", "items.city",
                            "items.incomingCount", "items.outgoingCount", "items.voicemailCount",
                            "items.total", "items.percent")
                    .generateStreaming(outputStream, (page, size) ->
                            page == 0 ? allGroups : Collections.emptyList(),
                    allGroups.size() + 1);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
