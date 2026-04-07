package com.infomedia.abacox.telephonypricing.component.export.excel;

import java.util.List;

/**
 * Functional interface for supplying data in pages to the streaming Excel generator.
 * <p>
 * Implementations should return the data for the requested page, or an empty list
 * when no more data is available.
 *
 * @param <T> the type of entities being exported
 */
@FunctionalInterface
public interface PagedDataSupplier<T> {

    /**
     * Returns the data for the given page.
     *
     * @param pageNumber zero-based page number
     * @param pageSize   number of items per page
     * @return the list of items for this page, or an empty list if no more data
     */
    List<T> getPage(int pageNumber, int pageSize);
}
