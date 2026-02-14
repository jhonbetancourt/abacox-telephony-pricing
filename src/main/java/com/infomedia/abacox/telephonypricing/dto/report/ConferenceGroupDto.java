package com.infomedia.abacox.telephonypricing.dto.report;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.infomedia.abacox.telephonypricing.constants.DateTimePattern;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConferenceGroupDto {
    private String conferenceId;

    @JsonFormat(pattern = DateTimePattern.DATE_TIME)
    private LocalDateTime conferenceServiceDate;

    private Long participantCount;
    private BigDecimal totalBilled;

    // Organizer info (from employee table, matched by dial = extension)
    private Long organizerId;
    private String organizerName;
    private String organizerExtension;
    private Long organizerSubdivisionId;
    private String organizerSubdivisionName;

    private List<ConferenceCallsReportDto> participants;
}
