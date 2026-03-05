package com.infomedia.abacox.telephonypricing.dto.generic;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Slice;

import java.util.Collections;
import java.util.List;

/**
 * Generic slice wrapper that includes a separate "summaries" list
 * alongside the normal slice content. Unlike {@link PageWithSummaries}, this
 * does not include totalElements / totalPages and avoids issuing a count query.
 *
 * @param <T> type of the slice content items
 * @param <S> type of the summary items
 */
@Data
@NoArgsConstructor
public class SliceWithSummaries<T, S> {

    private List<T> content;
    private int pageNumber;
    private int pageSize;
    private boolean last;
    private boolean first;
    private boolean empty;
    private boolean hasNext;
    private List<S> summaries;

    public SliceWithSummaries(Slice<T> slice, List<S> summaries) {
        this.content = slice.getContent();
        this.pageNumber = slice.getNumber();
        this.pageSize = slice.getSize();
        this.last = slice.isLast();
        this.first = slice.isFirst();
        this.empty = slice.isEmpty();
        this.hasNext = slice.hasNext();
        this.summaries = summaries != null ? summaries : Collections.emptyList();
    }

    public static <T, S> SliceWithSummaries<T, S> of(Slice<T> slice, List<S> summaries) {
        return new SliceWithSummaries<>(slice, summaries);
    }
}
