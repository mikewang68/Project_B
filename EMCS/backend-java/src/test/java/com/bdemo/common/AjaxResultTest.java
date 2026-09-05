package com.bdemo.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AjaxResultTest {
    @Test
    void keepsRuoYiEnvelopeAndAllowsTopLevelCompatibilityFields() {
        AjaxResult result = AjaxResult.success().add("token", "demo-token");

        assertThat(result).containsEntry("code", 200)
                .containsEntry("msg", "操作成功")
                .containsEntry("token", "demo-token");
    }
}
