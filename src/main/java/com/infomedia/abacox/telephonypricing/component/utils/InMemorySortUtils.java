package com.infomedia.abacox.telephonypricing.component.utils;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Comparator;
import java.util.List;

public class InMemorySortUtils {

    private InMemorySortUtils() {}

    /**
     * Returns a Pageable with the defaultSort applied when the requested Pageable has no sort.
     * Use this before passing a Pageable to a native query repository method.
     */
    public static Pageable applyDefaultSort(Pageable pageable, Sort defaultSort) {
        if (pageable.getSort().isSorted()) {
            return pageable;
        }
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), defaultSort);
    }

    /**
     * Sorts a list in-memory using the provided Sort, falling back to defaultSort
     * when the requested sort is unsorted.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <T> void sort(List<T> list, Sort requestedSort, Sort defaultSort) {
        Sort effectiveSort = requestedSort.isSorted() ? requestedSort : defaultSort;
        if (effectiveSort.isUnsorted() || list.isEmpty()) return;

        Comparator<T> comparator = null;
        for (Sort.Order order : effectiveSort) {
            final String property = order.getProperty();
            Comparator<T> fieldComp = (a, b) -> {
                Comparable valA = (Comparable) getFieldValue(a, property);
                Comparable valB = (Comparable) getFieldValue(b, property);
                if (valA == null && valB == null) return 0;
                if (valA == null) return 1;
                if (valB == null) return -1;
                return valA.compareTo(valB);
            };
            if (order.isDescending()) {
                fieldComp = fieldComp.reversed();
            }
            comparator = comparator == null ? fieldComp : comparator.thenComparing(fieldComp);
        }
        if (comparator != null) {
            list.sort(comparator);
        }
    }

    private static Object getFieldValue(Object obj, String fieldName) {
        Class<?> clazz = obj.getClass();
        while (clazz != null) {
            try {
                java.lang.reflect.Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(obj);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }
}
