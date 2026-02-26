package com.infomedia.abacox.telephonypricing.service.report;

import com.infomedia.abacox.telephonypricing.component.modeltools.ModelConverter;
import com.infomedia.abacox.telephonypricing.db.entity.ExtensionList;
import com.infomedia.abacox.telephonypricing.db.repository.ExtensionListRepository;
import com.infomedia.abacox.telephonypricing.db.repository.ReportRepository;
import com.infomedia.abacox.telephonypricing.dto.generic.PageWithSummaries;
import com.infomedia.abacox.telephonypricing.dto.report.ExtensionGroupDto;
import com.infomedia.abacox.telephonypricing.dto.report.ExtensionGroupReportDto;
import com.infomedia.abacox.telephonypricing.dto.report.ExtensionGroupSummaryDto;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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
     * Returns a page of ExtensionGroupDto (each with groupName + items list),
     * plus a single TOTALES summary across all groups.
     */
    @Transactional(readOnly = true)
    public PageWithSummaries<ExtensionGroupDto, ExtensionGroupSummaryDto> generateExtensionGroupReport(
            LocalDateTime startDate,
            LocalDateTime endDate,
            Long groupId,
            String voicemailNumber,
            List<Long> operatorIds,
            Pageable pageable) {

        // 1. Fetch active extension groups (optionally filtered by ID)
        List<ExtensionList> groups = extensionListRepository.findActiveByTypeAndOptionalId("EXT", groupId);

        if (groups.isEmpty()) {
            Page<ExtensionGroupDto> emptyPage = new PageImpl<>(Collections.emptyList(), pageable, 0);
            return PageWithSummaries.of(emptyPage, Collections.emptyList());
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
            Page<ExtensionGroupDto> emptyPage = new PageImpl<>(Collections.emptyList(), pageable, 0);
            return PageWithSummaries.of(emptyPage, Collections.emptyList());
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

        // 6. Calculate grand TOTALES summary
        int totalIncoming = allRows.stream().mapToInt(r -> r.getIncomingCount() != null ? r.getIncomingCount() : 0)
                .sum();
        int totalOutgoing = allRows.stream().mapToInt(r -> r.getOutgoingCount() != null ? r.getOutgoingCount() : 0)
                .sum();
        int totalVoicemail = allRows.stream().mapToInt(r -> r.getVoicemailCount() != null ? r.getVoicemailCount() : 0)
                .sum();
        int grandTotal = allRows.stream().mapToInt(r -> r.getTotal() != null ? r.getTotal() : 0).sum();

        ExtensionGroupSummaryDto totales = ExtensionGroupSummaryDto.builder()
                .label("TOTALES")
                .totalIncoming(totalIncoming)
                .totalOutgoing(totalOutgoing)
                .totalVoicemail(totalVoicemail)
                .total(grandTotal)
                .totalPercent(new BigDecimal("100.00"))
                .build();

        // 7. Paginate the group list
        int start = (int) pageable.getOffset();
        int end = Math.min((start + pageable.getPageSize()), groupDtos.size());
        List<ExtensionGroupDto> pageContent = start > groupDtos.size()
                ? Collections.emptyList()
                : groupDtos.subList(start, end);

        Page<ExtensionGroupDto> page = new PageImpl<>(pageContent, pageable, groupDtos.size());
        return PageWithSummaries.of(page, List.of(totales));
    }
}
