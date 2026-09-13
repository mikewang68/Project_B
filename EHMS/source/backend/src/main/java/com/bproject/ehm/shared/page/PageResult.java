package com.bproject.ehm.shared.page;

import java.util.List;
import java.util.function.Function;

public record PageResult<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public PageResult {
        content = List.copyOf(content);
    }

    public static <T> PageResult<T> of(List<T> content, PageQuery query, long totalElements) {
        int pages = query.size() == 0 ? 0 : (int) Math.ceil((double) totalElements / query.size());
        return new PageResult<>(content, query.page(), query.size(), totalElements, pages);
    }

    public <R> PageResult<R> map(Function<T, R> mapper) {
        return new PageResult<>(content.stream().map(mapper).toList(), page, size, totalElements, totalPages);
    }
}
