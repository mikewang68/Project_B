package com.bproject.ehm.shared.page;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PageQueryTest {
    @Test
    void normalizesInvalidAndExcessiveValues() {
        PageQuery defaults = new PageQuery(-1, 0);
        PageQuery capped = new PageQuery(2, 10_000);

        assertEquals(0, defaults.page());
        assertEquals(PageQuery.DEFAULT_SIZE, defaults.size());
        assertEquals(2, capped.page());
        assertEquals(PageQuery.MAX_SIZE, capped.size());
    }
}
