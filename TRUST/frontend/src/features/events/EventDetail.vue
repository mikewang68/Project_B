<script setup lang="ts">
import { toRef } from "vue";
import type { ApiRecord } from "../../shared/types";
import { useEventDetail } from "./useEventDetail";
import { typeLabels } from "./presentation";
import { fmt, label } from "../../shared/presentation";
import { exportEvent } from "../verification/api";
import EvidenceFiles from "../evidence/EvidenceFiles.vue";
import VerificationResult from "../verification/VerificationResult.vue";
const props = defineProps<{ id: string; canWrite: boolean }>();
const emit = defineEmits<{
  close: [];
  open: [id: string];
  correct: [record: ApiRecord];
}>();
const { selected, check, detailEvent, busy, run, verify } = useEventDetail(
  toRef(props, "id"),
);
</script>

<template>
  <template v-if="selected"
    ><div class="detail-bar">
      <button @click="emit('close')">← 返回列表</button>
      <div>
        <button @click="verify" :disabled="busy">核验证据</button
        ><button
          @click="
            run(() => exportEvent(selected!.id, selected!.source_event_id))
          "
          :disabled="busy || selected.chain_state !== 'COMMITTED'"
        >
          导出证据包</button
        ><button v-if="canWrite" @click="emit('correct', selected)">
          追加更正
        </button>
      </div>
    </div>
    <article class="panel">
      <div class="section-title">
        <h2>
          {{ typeLabels[selected.event_type] || selected.event_type }}
          <span class="muted">/ {{ selected.source_event_id }}</span>
        </h2>
        <span class="badge" :data-state="selected.chain_state">{{
          label(selected.chain_state)
        }}</span>
      </div>
      <dl class="facts">
        <div>
          <dt>批次</dt>
          <dd>{{ selected.batch_id }}</dd>
        </div>
        <div>
          <dt>业务对象</dt>
          <dd>{{ selected.object_id }}</dd>
        </div>
        <div>
          <dt>业务发生时间</dt>
          <dd>{{ fmt(selected.occurred_at) }}</dd>
        </div>
        <div>
          <dt>接收时间</dt>
          <dd>{{ fmt(selected.received_at) }}</dd>
        </div>
        <div>
          <dt>数量 / 单位</dt>
          <dd>{{ detailEvent.quantity ?? "—" }} {{ detailEvent.unit }}</dd>
        </div>
        <div>
          <dt>地点</dt>
          <dd>{{ detailEvent.location || "—" }}</dd>
        </div>
        <div>
          <dt>供应来源</dt>
          <dd>{{ detailEvent.supplier || "—" }}</dd>
        </div>
        <div>
          <dt>接收单位</dt>
          <dd>{{ detailEvent.receiver || "—" }}</dd>
        </div>
        <div>
          <dt>录入身份 / 来源</dt>
          <dd>{{ selected.submitted_by }} / {{ selected.source_system }}</dd>
        </div>
        <div>
          <dt>版本</dt>
          <dd>v{{ selected.version }}</dd>
        </div>
      </dl>
      <div class="state-path">
        <span :class="{ done: true }">事件已接收</span><i>→</i
        ><span :class="{ done: selected.file_state === 'STORED' }"
          >文件{{ label(selected.file_state) }}</span
        ><i>→</i
        ><span :class="{ done: selected.chain_state === 'COMMITTED' }">{{
          label(selected.chain_state)
        }}</span>
      </div>
      <p v-if="selected.last_error" class="alert error">
        {{ selected.last_error }}
      </p>
      <p v-if="selected.missingReferences?.length" class="alert warning">
        关联记录尚未到达：{{ selected.missingReferences.join("、") }}
      </p>
      <EvidenceFiles :evidence="selected.evidence" />
      <details class="technical">
        <summary>存证标识与历史版本</summary>
        <p>事件摘要</p>
        <code>{{ selected.event_sha256 }}</code>
        <p>证据清单 CID</p>
        <code>{{ selected.manifest_cid || "待保存" }}</code>
        <p>交易标识</p>
        <code>{{ selected.tx_id || "待提交" }}</code>
        <div class="version-list">
          <button
            v-for="v in selected.versions"
            :key="v.id"
            @click="emit('open', v.id)"
          >
            v{{ v.version }} · {{ label(v.chain_state) }}
          </button>
        </div>
      </details>
    </article>
    <VerificationResult :check="check"
  /></template>
</template>
