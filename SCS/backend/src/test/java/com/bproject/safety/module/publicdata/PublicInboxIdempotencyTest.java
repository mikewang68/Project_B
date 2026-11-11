package com.bproject.safety.module.publicdata;

import com.bproject.safety.module.publicdata.repository.InMemoryPublicInboxRepository;
import com.bproject.safety.module.publicdata.repository.PublicInboxRepository;
import com.bproject.safety.module.publicdata.service.PublicDeviceEventMapper;
import com.bproject.safety.module.publicdata.service.PublicInboxProcessorImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** 收件箱幂等与补偿语义：重复投递按状态收敛；映射失败保留原事件待重跑。 */
class PublicInboxIdempotencyTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private InMemoryPublicInboxRepository inbox;
    private PublicDeviceEventMapper mapper;
    private PublicInboxProcessorImpl processor;
    private AtomicInteger mapCalls;

    @BeforeEach
    void setup() {
        inbox = new InMemoryPublicInboxRepository();
        mapCalls = new AtomicInteger();
        mapper = mock(PublicDeviceEventMapper.class);
        org.mockito.Mockito.doAnswer(inv -> {
            mapCalls.incrementAndGet();
            return new PublicDeviceEventMapper.MappingResult(1, java.util.List.of(), "ok");
        }).when(mapper).map(any(JsonNode.class));
        processor = new PublicInboxProcessorImpl(inbox, mapper);
    }

    private JsonNode envelope(String eventId) throws Exception {
        return JSON.readTree("{\"schema_version\":\"b.device-committed.v1\",\"event_id\":\"" + eventId
                + "\",\"committed_ms\":1,\"wire_sha256\":\"w\",\"simulated\":true,"
                + "\"event\":{\"schema_version\":\"b.device-event.v1\",\"simulated\":true,"
                + "\"source_id\":\"SIM-0ece1fee47f641be93d4ac53\",\"payload\":{\"points\":[]}}}");
    }

    @Test
    @DisplayName("重复投递：PROCESSED 跳过（业务只执行一次）")
    void duplicateSkippedAfterProcessed() throws Exception {
        assertThat(processor.ensureInbox("E-1", 1, "w", JSON.readTree("{}"))).isTrue();
        processor.processMapped("E-1", envelope("E-1"));
        assertThat(inbox.statusOf("E-1")).isEqualTo("PROCESSED");

        assertThat(processor.ensureInbox("E-1", 1, "w", JSON.readTree("{}"))).isFalse();
        processor.processMapped("E-1", envelope("E-1"));
        assertThat(mapCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("映射失败：FAILED 行保留原事件，重跑补偿（FAILED/RECEIVED 均重新映射）")
    void failedRowRetainedForCompensation() throws Exception {
        org.mockito.Mockito.doThrow(new RuntimeException("字段映射规则尚未核对")).when(mapper).map(any(JsonNode.class));
        assertThat(processor.ensureInbox("E-1", 1, "w", JSON.readTree("{}"))).isTrue();
        try {
            processor.processMapped("E-1", envelope("E-1"));
        } catch (RuntimeException expected) {
            // 与消费器行为一致：失败原因落收件箱
        }
        processor.markFailed("E-1", "字段映射规则尚未核对");
        assertThat(inbox.statusOf("E-1")).isEqualTo("FAILED");

        // 修复后重跑（重投递/手动补偿）：FAILED 状态重新映射
        org.mockito.Mockito.doAnswer(inv -> new PublicDeviceEventMapper.MappingResult(1, java.util.List.of(), "ok"))
                .when(mapper).map(any(JsonNode.class));
        processor.processMapped("E-1", envelope("E-1"));
        assertThat(inbox.statusOf("E-1")).isEqualTo("PROCESSED");
        // verify times(2) = 首次失败调用 + 修复后补偿调用
        verify(mapper, times(2)).map(any(JsonNode.class));
    }

    @Test
    @DisplayName("不同 event_id 各自成箱，互不影响")
    void distinctEventsIndependent() throws Exception {
        processor.ensureInbox("E-1", 1, "w", JSON.readTree("{}"));
        processor.ensureInbox("E-2", 2, "w2", JSON.readTree("{}"));
        processor.processMapped("E-1", envelope("E-1"));
        processor.processMapped("E-2", envelope("E-2"));
        assertThat(inbox.countByStatus("PROCESSED")).isEqualTo(2);
        assertThat(mapCalls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("游标持久化：补读进度存取一致")
    void cursorPersistence() {
        PublicInboxRepository repo = new InMemoryPublicInboxRepository();
        assertThat(repo.backfillCursor()).isNull();
        repo.saveBackfillCursor("cursor-abc");
        assertThat(repo.backfillCursor()).isEqualTo("cursor-abc");
    }
}
