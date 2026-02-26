package com.infomedia.abacox.telephonypricing.dto.generic;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Page;

import java.util.Collections;
import java.util.List;

/**
 * Generic pagination wrapper that includes a separate "summaries" list
 * alongside the normal paginated content. The summary type (S) can be
 * different from the content type (T).
 *
 * @param <T> type of the paginated content items
 * @param <S> type of the summary items
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
public class PageWithSummaries<T, S> extends PageDto<T> {

    private List<S> summaries;

    public PageWithSummaries(Page<T> page, List<S> summaries) {
        super(page);
        this.summaries = summaries != null ? summaries : Collections.emptyList();
    }

    public static <T, S> PageWithSummaries<T, S> of(Page<T> page, List<S> summaries) {
        return new PageWithSummaries<>(page, summaries);
    }
}
