package com.infomedia.abacox.telephonypricing.service.report;

import com.infomedia.abacox.telephonypricing.component.export.excel.ExcelGeneratorBuilder;
import com.infomedia.abacox.telephonypricing.component.modeltools.ModelConverter;
import com.infomedia.abacox.telephonypricing.db.projection.ConferenceCandidateProjection;
import com.infomedia.abacox.telephonypricing.db.repository.ReportRepository;
import com.infomedia.abacox.telephonypricing.dto.report.ConferenceCallsReportDto;
import com.infomedia.abacox.telephonypricing.dto.report.ConferenceGroupDto;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Service
public class ConferenceReportService {

    private final ReportRepository reportRepository;
    private final ModelConverter modelConverter;

    @Transactional(readOnly = true)
    public Page<ConferenceGroupDto> generateConferenceCallsReport(
            LocalDateTime startDate, LocalDateTime endDate,
            String extension, String employeeName,
            Pageable pageable) {

        List<ConferenceCandidateProjection> rows = reportRepository.findConferenceCandidates(startDate, endDate,
                extension, employeeName);

        List<ConferenceGroupDto> allGroups = groupCandidates(rows);

        int start = (int) pageable.getOffset();
        int end = Math.min((start + pageable.getPageSize()), allGroups.size());

        List<ConferenceGroupDto> pageContent;
        if (start > allGroups.size()) {
            pageContent = Collections.emptyList();
        } else {
            pageContent = allGroups.subList(start, end);
        }

        return new PageImpl<>(pageContent, pageable, allGroups.size());
    }

    private List<ConferenceGroupDto> groupCandidates(List<ConferenceCandidateProjection> rows) {
        List<ConferenceGroupDto> completedGroups = new ArrayList<>();
        if (rows == null || rows.isEmpty()) {
            return completedGroups;
        }

        Map<String, List<GroupContext>> activeGroups = new HashMap<>();
        List<GroupContext> allContexts = new ArrayList<>();

        for (ConferenceCandidateProjection row : rows) {
            String transferKey = row.getTransferKey();
            Integer transferCause = row.getTransferCause();
            LocalDateTime serviceDate = row.getServiceDate();
            Integer duration = row.getDuration() != null ? row.getDuration() : 0;
            LocalDateTime rowEnd = serviceDate.plusSeconds(duration);

            String groupingIdentity;
            if (transferCause != null && transferCause == 10) {
                groupingIdentity = row.getDialedNumber();
            } else {
                groupingIdentity = row.getEmployeeExtension();
            }

            GroupContext matchedContext = null;
            List<GroupContext> candidates = activeGroups.computeIfAbsent(transferKey, k -> new ArrayList<>());

            for (GroupContext context : candidates) {
                if (Objects.equals(groupingIdentity, context.groupingIdentity)) {
                    if (!serviceDate.isAfter(context.lastActiveTime)) {
                        matchedContext = context;
                        break;
                    }
                }
            }

            if (matchedContext != null) {
                if (transferCause != null && transferCause == 10 &&
                        Objects.equals(row.getEmployeeExtension(), matchedContext.group.getOrganizerExtension())) {
                    // Skip organizer record in Type 10
                } else {
                    addParticipantToGroup(matchedContext.group, row, transferCause);
                }

                if (rowEnd.isAfter(matchedContext.lastActiveTime)) {
                    matchedContext.lastActiveTime = rowEnd;
                }
            } else {
                ConferenceGroupDto newGroup = createGroupFromRow(row, groupingIdentity, transferCause);
                GroupContext newContext = new GroupContext(newGroup, rowEnd, groupingIdentity);

                if (transferCause != null && transferCause == 10 &&
                        Objects.equals(row.getEmployeeExtension(), groupingIdentity)) {
                    // Skip organizer
                } else {
                    addParticipantToGroup(newGroup, row, transferCause);
                }

                candidates.add(newContext);
                allContexts.add(newContext);
            }
        }

        return allContexts.stream()
                .map(ctx -> ctx.group)
                .filter(g -> g.getParticipantCount() > 1)
                .sorted(Comparator.comparing(ConferenceGroupDto::getConferenceServiceDate).reversed())
                .collect(Collectors.toList());
    }

    private ConferenceGroupDto createGroupFromRow(ConferenceCandidateProjection row, String groupingIdentity,
            Integer transferCause) {
        ConferenceGroupDto group = new ConferenceGroupDto();
        group.setTransferKey(row.getTransferKey());

        if (transferCause != null && transferCause == 10) {
            group.setOrganizerExtension(row.getDialedNumber());
            group.setOrganizerId(row.getOrganizerId());
            group.setOrganizerName(row.getOrganizerName());
            group.setOrganizerSubdivisionId(row.getOrganizerSubdivisionId());
            group.setOrganizerSubdivisionName(row.getOrganizerSubdivisionName());
        } else {
            group.setOrganizerExtension(row.getEmployeeExtension());
            group.setOrganizerId(row.getEmployeeId());
            group.setOrganizerName(row.getEmployeeName());
            group.setOrganizerSubdivisionId(row.getSubdivisionId());
            group.setOrganizerSubdivisionName(row.getSubdivisionName());
        }

        group.setParticipantCount(0L);
        group.setTotalBilled(BigDecimal.ZERO);
        group.setConferenceServiceDate(row.getServiceDate());
        group.setParticipants(new ArrayList<>());
        return group;
    }

    private void addParticipantToGroup(ConferenceGroupDto group, ConferenceCandidateProjection row,
            Integer transferCause) {
        ConferenceCallsReportDto dto = modelConverter.map(row, ConferenceCallsReportDto.class);

        if (transferCause != null && transferCause == 10) {
            String originalExt = row.getEmployeeExtension();
            String originalDial = row.getDialedNumber();
            dto.setEmployeeExtension(originalExt);
            dto.setDialedNumber(originalDial);
        } else {
            dto.setEmployeeExtension(row.getDialedNumber());
            dto.setEmployeeId(row.getOrganizerId());
            dto.setEmployeeName(row.getOrganizerName());
            dto.setSubdivisionId(row.getOrganizerSubdivisionId());
            dto.setSubdivisionName(row.getOrganizerSubdivisionName());
        }

        group.getParticipants().add(dto);
        group.setParticipantCount(group.getParticipantCount() + 1L);
        if (dto.getBilledAmount() != null) {
            group.setTotalBilled(group.getTotalBilled().add(dto.getBilledAmount()));
        }
    }

    private static class GroupContext {
        ConferenceGroupDto group;
        LocalDateTime lastActiveTime;
        String groupingIdentity;

        GroupContext(ConferenceGroupDto group, LocalDateTime lastActiveTime, String groupingIdentity) {
            this.group = group;
            this.lastActiveTime = lastActiveTime;
            this.groupingIdentity = groupingIdentity;
        }
    }

    @Transactional(readOnly = true)
    public ByteArrayResource exportExcelConferenceCallsReport(
            LocalDateTime startDate, LocalDateTime endDate,
            String extension, String employeeName,
            Pageable pageable, ExcelGeneratorBuilder builder) {
        Page<ConferenceGroupDto> collection = generateConferenceCallsReport(
                startDate, endDate, extension, employeeName, pageable);
        try {
            InputStream inputStream = builder
                    .withEntities(collection.toList())
                    .withFlattenedCollection("participants")
                    .withIncludedFields(
                            "transferKey", "conferenceServiceDate", "participantCount", "totalBilled",
                            "organizerId", "organizerName", "organizerExtension", "organizerSubdivisionId",
                            "organizerSubdivisionName",
                            "participants.serviceDate", "participants.employeeExtension", "participants.duration",
                            "participants.billedAmount", "participants.employeeAuthCode", "participants.employeeName",
                            "participants.subdivisionName", "participants.telephonyTypeName",
                            "participants.contactName",
                            "participants.companyName")
                    .generateAsInputStream();
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
