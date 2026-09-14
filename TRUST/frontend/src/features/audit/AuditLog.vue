<script setup lang="ts">
import type { ApiRecord } from "../../shared/types";
import { fmt } from "../../shared/presentation";
defineProps<{ audit: ApiRecord[] }>();
</script>

<template>
  <article class="panel">
    <h2>最近操作</h2>
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>时间</th>
            <th>操作人</th>
            <th>操作</th>
            <th>记录</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="a in audit" :key="a.id">
            <td>{{ fmt(a.happened_at) }}</td>
            <td>{{ a.actor }}</td>
            <td>
              {{
                (
                  {
                    EVENT_SUBMIT: "接收事件",
                    EVENT_CORRECT: "追加更正",
                    UPLOAD: "上传证据",
                    DOWNLOAD: "下载证据",
                    VERIFY: "核验",
                    EXPORT: "导出",
                    RETRY: "补办",
                  } as ApiRecord
                )[a.action] || a.action
              }}
            </td>
            <td class="mono">{{ a.object_id }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </article>
</template>
