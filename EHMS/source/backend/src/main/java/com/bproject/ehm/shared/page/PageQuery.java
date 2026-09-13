package com.bproject.ehm.shared.page;

public record PageQuery(int page, int size) {
    public static final int DEFAULT_SIZE = 50;
    public static final int MAX_SIZE = 200;

    public PageQuery {
        page = Math.max(page, 0);
        size = size <= 0 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
    }
}
