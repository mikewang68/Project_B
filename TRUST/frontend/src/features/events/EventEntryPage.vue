<script setup lang="ts">
import { toRef } from "vue";
import type { ApiRecord } from "../../shared/types";
import { useEventEntry } from "./useEventEntry";
import { typeLabels } from "./presentation";
const props = defineProps<{ canWrite: boolean; previous: ApiRecord | null }>();
const emit = defineEmits<{ saved: [id: string]; cancel: [] }>();
const {
  form,
  uploaded,
  imports,
  correction,
  busy,
  uploadFiles,
  submitEvent,
  importFile,
  reset,
} = useEventEntry(
  toRef(props, "previous"),
  (id) => emit("saved", id),
  () => emit("cancel"),
);
</script>

<template>
  <p v-if="!canWrite" class="alert warning">
    当前账号可以查询和核验，录入需要录入员或管理员权限。
  </p>
  <div v-else class="create-layout">
    <form class="panel" @submit.prevent="submitEvent">
      <div class="section-title">
        <h2>{{ correction ? "追加更正" : "录入业务事件" }}</h2>
        <button v-if="correction" type="button" @click="reset()">
          取消更正
        </button>
      </div>
      <p v-if="correction" class="alert warning">
        提交后保留原记录，新增关联版本。
      </p>
      <div class="form-grid">
        <label
          >来源系统<input
            v-model="form.sourceSystem"
            required
            pattern="[A-Za-z0-9._-]+" /></label
        ><label
          >来源事件号<input
            v-model="form.sourceEventId"
            required
            pattern="[A-Za-z0-9._-]+"
            placeholder="如 ARRIVAL-001" /></label
        ><label
          >事件类型<select v-model="form.eventType">
            <option v-for="(name, key) in typeLabels" :key="key" :value="key">
              {{ name }}
            </option>
          </select></label
        ><label
          >业务发生时间<input
            v-model="form.occurredAt"
            type="datetime-local"
            required /></label
        ><label>批次号<input v-model="form.batchId" required /></label
        ><label
          >业务对象编号<input v-model="form.businessObjectId" required /></label
        ><label
          >数量<input
            v-model="form.quantity"
            type="number"
            min="0"
            step="0.000001" /></label
        ><label>单位<input v-model="form.unit" /></label
        ><label>作业地点<input v-model="form.location" /></label
        ><label>交接单号<input v-model="form.handoverId" /></label
        ><label>供应来源<input v-model="form.supplier" /></label
        ><label>接收单位<input v-model="form.receiver" /></label
        ><label>捆号（逗号分隔）<input v-model="form.bundleText" /></label
        ><label
          >关联批次（逗号分隔）<input v-model="form.relatedBatchText" /></label
        ><label class="wide"
          >关联事件（来源系统:事件号，逗号分隔）<input
            v-model="form.relatedEventText"
            placeholder="MANUAL:ARRIVAL-001" /></label
        ><label class="wide"
          >备注<textarea v-model="form.note" rows="3"></textarea>
        </label>
      </div>
      <h3>附加证据</h3>
      <label class="upload-box"
        >选择 PDF / PNG / JPEG 文件 <span>单文件不超过 20 MiB</span
        ><input
          type="file"
          multiple
          accept=".pdf,.png,.jpg,.jpeg"
          @change="uploadFiles"
          :disabled="busy"
      /></label>
      <div v-for="(ev, i) in uploaded" :key="ev.id" class="file-row">
        <span>{{ ev.filename }}</span
        ><button type="button" @click="uploaded.splice(i, 1)">移除引用</button>
      </div>
      <button class="primary" :disabled="busy">提交事件</button>
    </form>
    <article class="panel import-panel">
      <h2>批量导入</h2>
      <p class="muted">
        导入 UTF-8 JSON 数组或 CSV。每次最多 200 条、1 MiB；每行单独返回结果。
      </p>
      <label class="upload-box"
        >选择导入文件<input
          type="file"
          accept=".json,.csv"
          @change="importFile"
          :disabled="busy"
      /></label>
      <p class="small muted">
        CSV 多值字段用 | 分隔。来源事件号用于避免重复登记。
      </p>
      <div v-if="imports">
        <h3>已接收 {{ imports.accepted }} 条</h3>
        <div v-for="r in imports.results" :key="r.row" class="check-row">
          <span :class="r.ok ? 'check-ok' : 'check-fail'"
            >第 {{ r.row }} 行</span
          ><span>{{ r.ok ? "已接收" : r.message }}</span>
        </div>
      </div>
    </article>
  </div>
</template>
