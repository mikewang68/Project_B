<!--
  节能建议闭环｜第四幕必演页
  REQ-045 模板与告警转建议 · REQ-046 建议内容 · REQ-047 流转/三档关闭
  REQ-048 四维验证 · REQ-049 后端优先级 · REQ-050 归档复盘
-->
<template>
  <div class="act4-page suggestion-page cockpit-page">
    <header class="filter-bar">
      <div class="filter-title">
        <b>节能建议闭环</b>
        <span>五档流转 · 三档关闭 · REQ-045–050</span>
      </div>
      <el-select v-model="filters.status" clearable placeholder="状态" size="small">
        <el-option v-for="item in statusOptions" :key="item.value" v-bind="item" />
      </el-select>
      <el-select v-model="filters.sourceType" clearable placeholder="来源" size="small">
        <el-option label="规则触发" value="rule" />
        <el-option label="人工创建" value="manual" />
      </el-select>
      <el-input v-model="filters.ruleCode" clearable placeholder="规则编号" size="small" />
      <el-select v-model="filters.zone" placeholder="区域" size="small">
        <el-option label="全部区域" value="ALL" />
        <el-option label="A 区" value="A" />
        <el-option label="B 区" value="B" />
      </el-select>
      <el-select v-model="filters.priorityBand" clearable placeholder="优先级" size="small">
        <el-option v-for="item in priorityOptions" :key="item.value" v-bind="item" />
      </el-select>
      <el-button type="primary" size="small" :loading="loading" @click="search">查询</el-button>
      <el-button size="small" @click="resetFilters">重置</el-button>
    </header>

    <section class="summary-strip">
      <button
        v-for="column in boardColumnDefinitions"
        :key="column.key"
        class="summary-cell"
        type="button"
        @click="filterByColumn(column.key)"
      >
        <span>{{ column.label }}</span>
        <b>{{ countForColumn(column.key) }}</b>
      </button>
      <button class="summary-cell deferred-entry" type="button" @click="showDeferred">
        <span>延期中</span><b>{{ boardCounts.deferred ?? 0 }}</b><small>不占看板列 · 点击独立查看</small>
      </button>
    </section>

    <section v-if="listError" class="page-state error-state">
      <b>建议列表暂时无法加载</b>
      <span>{{ listError }}</span>
      <el-button size="small" @click="loadBoard">重试</el-button>
    </section>

    <section v-if="filters.status === 'deferred'" v-loading="loading" class="panel deferred-panel">
      <header class="panel-head">
        <div><div class="panel-title">延期建议</div><div class="panel-sub">独立状态列表 · 不增加看板列</div></div>
        <span class="count-label">共 {{ total }} 条</span>
      </header>
      <div v-if="itemsForColumn(items, 'deferred').length" class="deferred-list">
        <button
          v-for="item in itemsForColumn(items, 'deferred')"
          :key="item.suggestionId"
          class="suggestion-card deferred-card"
          :class="`priority-band-${item.priority?.band || 'na'}`"
          type="button"
          @click="openDetail(item.suggestionId)"
        >
          <div class="card-head">
            <span class="card-eyebrow">{{ sourceLabel(item.sourceType) }} · {{ item.area?.name || item.area?.code || '全站' }}</span>
            <span class="priority-tag" :class="`priority-${item.priority?.band}`">
              {{ priorityBandLabels[item.priority?.band] || item.priority?.band || '—' }}
              <em>{{ formatScore(item.priority?.score) }}</em>
            </span>
          </div>
          <b class="card-title">{{ item.title }}</b>
          <span class="measure-preview">{{ item.sourceSummary || '—' }}</span>
          <div class="card-footer">
            <span class="status-chip">延期中</span>
            <span class="update-time">{{ formatDateTime(item.updatedAt) }}</span>
          </div>
        </button>
      </div>
      <div v-else class="empty-state">当前筛选条件下没有延期建议</div>
    </section>

    <section v-else v-loading="loading" class="board" aria-label="节能建议五列看板">
      <article
        v-for="column in boardColumnDefinitions"
        :key="column.key"
        class="board-column"
        :class="`col-${column.key}`"
      >
        <header>
          <div class="col-title">
            <b>{{ column.label }}</b>
            <small>{{ columnSubtitle(column.key) }}</small>
          </div>
          <span class="col-count">{{ countForColumn(column.key) }}</span>
        </header>
        <div class="card-stack">
          <button
            v-for="item in itemsForColumn(items, column.key)"
            :key="item.suggestionId"
            class="suggestion-card"
            :class="`priority-band-${item.priority?.band || 'na'}`"
            type="button"
            @click="openDetail(item.suggestionId)"
          >
            <div class="card-head">
              <span class="card-eyebrow">{{ sourceLabel(item.sourceType) }} · {{ item.area?.name || item.area?.code || '全站' }}</span>
              <span class="priority-tag" :class="`priority-${item.priority?.band}`">
                {{ priorityBandLabels[item.priority?.band] || item.priority?.band || '—' }}
                <em>{{ formatScore(item.priority?.score) }}</em>
              </span>
            </div>
            <b class="card-title">{{ item.title }}</b>
            <span class="measure-preview">{{ item.measureContent }}</span>
            <dl class="card-facts">
              <div><dt>对象</dt><dd>{{ item.equipment?.name || item.equipment?.code || item.area?.name || '全站' }}</dd></div>
              <div><dt>责任人</dt><dd>{{ item.responsibleUser || '待分派' }}</dd></div>
              <div><dt>验证期</dt><dd>{{ formatPeriod(item.verifyStart, item.verifyEnd) }}</dd></div>
            </dl>
            <div class="card-footer">
              <span v-if="item.closeLabel" class="close-tag">{{ item.closeLabel }}</span>
              <span v-else class="status-chip">{{ suggestionStatusLabels[item.status] || item.status }}</span>
              <span class="update-time">{{ formatDateTime(item.updatedAt) }}</span>
            </div>
          </button>
          <div v-if="!itemsForColumn(items, column.key).length" class="column-empty">当前列暂无建议</div>
        </div>
      </article>
    </section>

    <el-pagination
      v-if="total > filters.pageSize"
      v-model:current-page="filters.pageNum"
      v-model:page-size="filters.pageSize"
      class="pager"
      layout="prev, pager, next, total"
      :total="total"
      @current-change="loadBoard"
    />

    <section class="panel retrospective-panel">
      <header class="panel-head">
        <div><div class="panel-title">归档复盘</div><div class="panel-sub">有效 · 无效 · 长期未执行 · 重复建议 · REQ-050</div></div>
        <div class="retrospective-actions">
          <el-date-picker
            v-model="retrospectiveMonth"
            type="month"
            value-format="YYYY-MM"
            format="YYYY-MM"
            placeholder="复盘月份"
            size="small"
            :clearable="false"
            @change="loadRetrospective"
          />
          <el-button text size="small" :loading="retrospectiveLoading" @click="loadRetrospective">刷新</el-button>
        </div>
      </header>
      <div v-if="retrospectiveError" class="inline-error">{{ retrospectiveError }} <button type="button" @click="loadRetrospective">重试</button></div>
      <template v-else-if="retrospective">
        <div class="close-type-section">
          <span>关闭类型分布</span>
          <div class="close-type-counts">
            <div><small>实施完成</small><b>{{ retrospective.closeTypeCounts?.implemented ?? 0 }}</b></div>
            <div><small>驳回</small><b>{{ retrospective.closeTypeCounts?.rejected ?? 0 }}</b></div>
            <div><small>归档无效</small><b>{{ retrospective.closeTypeCounts?.archivedInvalid ?? 0 }}</b></div>
          </div>
        </div>
        <div class="retrospective-metrics">
          <div><span>实施有效率</span><b>{{ formatRate(retrospective.effectiveRate) }}</b></div>
          <div><span>无效数量</span><b>{{ retrospective.ineffectiveCount ?? '—' }}</b></div>
          <div><span>长期未执行</span><b>{{ retrospective.unexecutedCount ?? '—' }}</b></div>
          <div><span>重复建议</span><b>{{ retrospective.duplicateCount ?? '—' }}</b></div>
        </div>
        <div v-if="retrospective.ruleOptimizationHints?.length" class="hint-list">
          <article v-for="(hint, index) in retrospective.ruleOptimizationHints" :key="`${hint.ruleCode || 'hint'}-${index}`">
            <b>{{ hint.ruleCode || '通用' }} · {{ hint.title || hint.category || '复核提示' }}</b>
            <span>{{ displayValue(hint.basis ?? hint.statisticalBasis ?? hint) }}</span>
            <small>{{ hint.reviewDirection || hint.suggestion || '' }}</small>
          </article>
        </div>
        <div v-else class="empty-state compact-empty">暂无规则化复盘提示</div>
      </template>
      <div v-else class="empty-state compact-empty">暂无归档复盘数据</div>
    </section>

    <el-drawer
      v-model="detailVisible"
      size="min(980px, 94vw)"
      class="cockpit-modal"
      destroy-on-close
      @closed="clearDetailQuery"
    >
      <template #header>
        <div v-if="detail" class="drawer-title">
          <span class="status-tag">{{ suggestionStatusLabels[detail.suggestion?.status] || detail.suggestion?.status }}</span>
          <div>
            <b>{{ detail.suggestion?.title }}</b>
            <small>建议 {{ detail.suggestion?.suggestionId }} · {{ sourceLabel(detail.suggestion?.sourceType) }}</small>
          </div>
        </div>
        <div v-else class="drawer-title"><div><b>节能建议详情</b><small>REQ-045–050</small></div></div>
      </template>

      <div v-loading="detailLoading" class="drawer-shell">
        <section v-if="detailError" class="page-state error-state detail-error">
          <b>{{ detailErrorTitle }}</b><span>{{ detailError }}</span>
          <el-button v-if="activeSuggestionId" size="small" @click="loadDetail(activeSuggestionId)">重试</el-button>
        </section>

        <div v-else-if="detail" class="detail-body">
          <section v-if="writePermissionError" class="page-state permission-state" role="alert">
            <b>写操作已被权限拦截</b>
            <span>{{ writePermissionError }}</span>
          </section>

          <section v-else class="detail-actions" aria-label="后端授权的建议动作">
            <el-button
              v-for="action in visibleActions"
              :key="action.value"
              :type="action.primary ? 'primary' : 'default'"
              :loading="actionLoading === action.value"
              @click="openAction(action.value)"
            >
              {{ action.label }}
            </el-button>
            <span v-if="!visibleActions.length" class="readonly-hint">当前账号无可执行的业务动作</span>
          </section>

          <section class="detail-grid">
            <article class="panel">
              <header class="panel-head"><div><div class="panel-title">冻结来源证据</div><div class="panel-sub">来源告警变化后仍可反查 · REQ-045</div></div></header>
              <dl class="facts">
                <div><dt>来源</dt><dd>{{ sourceLabel(detail.suggestion.sourceType) }}</dd></div>
                <div><dt>规则</dt><dd>{{ sourceSnapshot.ruleCode || detail.suggestion.ruleCode || '—' }} · v{{ sourceSnapshot.ruleVersion || '—' }}</dd></div>
                <div><dt>对象</dt><dd>{{ sourceObjectLabel }}</dd></div>
                <div><dt>区域</dt><dd>{{ sourceSnapshot.area?.name || sourceSnapshot.area?.code || detail.suggestion.area?.name || '全站' }}</dd></div>
                <div><dt>首次触发</dt><dd>{{ formatDateTime(sourceSnapshot.firstOccurredAt) }}</dd></div>
                <div><dt>最近触发</dt><dd>{{ formatDateTime(sourceSnapshot.lastOccurredAt) }}</dd></div>
                <div><dt>合并次数</dt><dd>{{ sourceSnapshot.occurCount ?? '—' }}</dd></div>
                <div><dt>来源指纹</dt><dd class="mono-value">{{ shortSignature(detail.suggestion.sourceFingerprint) }}</dd></div>
                <div class="wide"><dt>触发依据</dt><dd>{{ detail.suggestion.triggerBasis || sourceSnapshot.triggerBasis || '—' }}</dd></div>
              </dl>
              <el-button v-if="currentSourceEventId" text type="primary" @click="goSourceAlert">查看当前关联告警</el-button>
              <el-button v-if="sourceSnapshot.kind === 'costAnomaly' && sourceSnapshot.deepLink" text type="primary" @click="goCostEvidence">返回成本证据</el-button>
              <span v-else-if="detail.suggestion.sourceType === 'rule'" class="snapshot-note">当前告警不可用，已保留冻结证据</span>
            </article>

            <article class="panel">
              <header class="panel-head"><div><div class="panel-title">冻结模板与措施</div><div class="panel-sub">历史实例不随模板版本变化 · REQ-045/046</div></div></header>
              <dl class="facts">
                <div><dt>模板</dt><dd>{{ templateSnapshot.templateName || '人工完整快照' }}</dd></div>
                <div><dt>版本</dt><dd>{{ templateSnapshot.templateCode || '—' }} · v{{ templateSnapshot.version || detail.suggestion.templateVersion || '—' }}</dd></div>
                <div><dt>分类</dt><dd>{{ templateSnapshot.category || '—' }}</dd></div>
                <div><dt>适用对象</dt><dd>{{ templateSnapshot.applicableObjectType || detail.suggestion.object?.type || '—' }}</dd></div>
                <div class="wide"><dt>建议措施</dt><dd>{{ detail.suggestion.measureContent || templateSnapshot.actionContent || '—' }}</dd></div>
                <div class="wide"><dt>所需数据</dt><dd>{{ templateSnapshot.requiredData || '—' }}</dd></div>
                <div><dt>预估节能</dt><dd>{{ templateSnapshot.estimatedSaving || '—' }}</dd></div>
                <div><dt>成本影响</dt><dd>{{ templateSnapshot.costImpact || '—' }}</dd></div>
                <div><dt>可靠性影响</dt><dd>{{ templateSnapshot.reliabilityImpact || '—' }}</dd></div>
                <div><dt>验证方法</dt><dd>{{ templateSnapshot.verificationMethod || '—' }}</dd></div>
              </dl>
            </article>
          </section>

          <section class="panel">
            <header class="panel-head"><div><div class="panel-title">责任与优先级依据</div><div class="panel-sub">五因子、权重与综合分均由后端返回 · REQ-047/049</div></div></header>
            <div class="responsibility-row">
              <span>责任人 <b>{{ detail.suggestion.responsibleUser || '待分派' }}</b></span>
              <span>责任角色 <b>{{ detail.suggestion.responsibleRole || '—' }}</b></span>
              <span>验证周期 <b>{{ formatPeriod(detail.suggestion.verifyStart, detail.suggestion.verifyEnd) }}</b></span>
              <span>优先级 <b>{{ priorityBandLabels[detail.suggestion.priority?.band] || detail.suggestion.priority?.band }} · {{ formatScore(detail.suggestion.priority?.score) }}</b></span>
            </div>
            <div class="factor-grid">
              <div v-for="factor in priorityFactorRows" :key="factor.key">
                <span>{{ factor.label }}</span><b>{{ factor.value ?? '—' }}</b><small>权重 {{ formatWeight(factor.weight) }}</small>
              </div>
            </div>
            <div class="formula-note">
              {{ detail.suggestion.priority?.formulaVersion || '—' }} · {{ displayValue(detail.suggestion.priority?.basis || {}) }}
            </div>
          </section>

          <section class="panel">
            <header class="panel-head"><div><div class="panel-title">人工执行时间线</div><div class="panel-sub">严格按 flowId 排序，不按同刻时间戳重排 · REQ-047</div></div></header>
            <el-timeline v-if="sortedFlows.length">
              <el-timeline-item
                v-for="flow in sortedFlows"
                :key="flow.flowId"
                :timestamp="`${formatDateTime(flow.occurTime)} · #${flow.flowId}`"
              >
                <b>{{ actionLabel(flow.action) }} · {{ statusTransitionLabel(flow) }}</b>
                <div class="timeline-meta">{{ flow.operator }}（{{ flow.operatorRole || '—' }}） · {{ flow.remark || '无备注' }}</div>
                <div v-if="flow.payloadSnapshot && Object.keys(flow.payloadSnapshot).length" class="timeline-payload">
                  {{ displayValue(flow.payloadSnapshot) }}
                </div>
              </el-timeline-item>
            </el-timeline>
            <div v-else class="empty-state">暂无执行留痕</div>
          </section>

          <section class="panel verification-panel">
            <header class="panel-head">
              <div><div class="panel-title">四维验证快照</div><div class="panel-sub">用量 · 成本 · 作业量 · 数据质量 · REQ-048</div></div>
              <span v-if="latestVerification" class="status-tag">{{ verificationStatusLabels[latestVerification.status] || latestVerification.status }}</span>
            </header>
            <div v-if="!latestVerification" class="empty-state">尚未生成验证快照</div>
            <template v-else>
              <div v-if="latestVerification && latestVerification.status === 'insufficient'" class="quality-warning">
                数据质量不足：{{ latestVerification.calculationNote || '接口未返回更多原因' }}。本次不展示确定性效果结论。
              </div>
              <div class="verification-meta">
                <span>基线 {{ formatPeriod(latestVerification.baselineStart, latestVerification.baselineEnd) }}</span>
                <span>报告 {{ formatPeriod(latestVerification.reportStart, latestVerification.reportEnd) }}</span>
                <span>{{ latestVerification.formulaVersion }} · {{ latestVerification.signature }}</span>
              </div>
              <div class="chart-grid" :class="{ muted: latestVerification.status === 'insufficient' }">
                <article><b>用量对比</b><div ref="usageChartEl" class="comparison-chart" /></article>
                <article><b>成本影响</b><div ref="costChartEl" class="comparison-chart" /></article>
                <article><b>作业量对照</b><div ref="workloadChartEl" class="comparison-chart" /></article>
              </div>
              <div class="quality-grid">
                <div><span>基线覆盖率</span><b>{{ formatRate(qualityComparison.baseline?.coveragePct) }}</b><small>{{ qualityLevel(qualityComparison.baseline) }}</small></div>
                <div><span>报告覆盖率</span><b>{{ formatRate(qualityComparison.report?.coveragePct) }}</b><small>{{ qualityLevel(qualityComparison.report) }}</small></div>
                <div><span>质量口径</span><b>{{ (qualityComparison.validQualityCodes || []).join(' / ') || '—' }}</b><small>接口快照</small></div>
              </div>
              <div v-if="latestVerification.status !== 'insufficient'" class="effect-result">
                <b>{{ verificationStatusLabels[latestVerification.status] || latestVerification.status }}</b>
                <span>节能量 {{ formatSaving(latestVerification.savingValue, latestVerification.savingUnit) }} · 用量变化 {{ formatRate(latestVerification.usageComparison?.savingPct) }}</span>
              </div>
              <div class="calculation-note">
                <b>节能量计算说明</b>
                <span>{{ latestVerification.calculationNote || '接口未返回计算说明' }}</span>
              </div>
            </template>
          </section>

          <section v-if="detail.closeInfo" class="panel close-panel">
            <header class="panel-head"><div><div class="panel-title">关闭归档</div><div class="panel-sub">责任对象 · 验证周期 · 复盘结论可反查 · REQ-047/050</div></div></header>
            <dl class="facts">
              <div><dt>关闭类型</dt><dd>{{ detail.closeInfo.closeLabel || detail.closeInfo.closeType }}</dd></div>
              <div><dt>关闭人 / 时间</dt><dd>{{ detail.closeInfo.closedBy || '—' }} · {{ formatDateTime(detail.closeInfo.closedAt) }}</dd></div>
              <div class="wide"><dt>关闭原因</dt><dd>{{ detail.closeInfo.closeReason || detail.closeInfo.rejectionReason || '—' }}</dd></div>
              <div><dt>无效分类</dt><dd>{{ detail.closeInfo.invalidCategory || '—' }}</dd></div>
              <div><dt>归档节能量</dt><dd>{{ formatSaving(detail.closeInfo.savingValue, detail.closeInfo.savingUnit) }}</dd></div>
              <div class="wide"><dt>效果复盘</dt><dd>{{ detail.closeInfo.effectSummary || '—' }}</dd></div>
            </dl>
            <div v-if="detail.closeInfo.attachments?.length" class="attachment-list">
              <a v-for="attachment in detail.closeInfo.attachments" :key="attachment.url || attachment.name" :href="attachment.url" target="_blank" rel="noreferrer">
                {{ attachment.name || attachment.url }}
              </a>
            </div>
          </section>
        </div>

        <div v-else-if="!detailLoading" class="empty-state">暂无建议详情</div>
      </div>
    </el-drawer>

    <el-dialog v-model="transitionVisible" :title="transitionDialogTitle" width="580px" append-to-body :close-on-click-modal="false">
      <el-form label-position="top">
        <el-form-item v-if="transitionForm.toStatus === 'dispatched'" label="人工执行责任人" required>
          <el-input v-model="transitionForm.assignedTo" placeholder="请输入责任人账号" />
        </el-form-item>
        <template v-if="transitionForm.toStatus === 'verifying'">
          <el-form-item v-if="serverFixedVerificationWindow" label="验证周期">
            <span class="fixed-window-hint">R06 固定验证窗口由服务端按冻结口径生成，页面不提交日期。</span>
          </el-form-item>
          <el-form-item v-else label="验证周期（可选）">
            <el-date-picker
              v-model="verificationPeriod"
              type="daterange"
              value-format="YYYY-MM-DD"
              range-separator="至"
              start-placeholder="开始日期"
              end-placeholder="结束日期"
            />
          </el-form-item>
        </template>
        <template v-if="transitionForm.toStatus === 'deferred'">
          <el-form-item label="延期原因" required><el-input v-model="transitionForm.deferReason" type="textarea" :rows="2" /></el-form-item>
          <el-form-item label="恢复日期" required><el-date-picker v-model="transitionForm.deferUntil" type="date" value-format="YYYY-MM-DD" /></el-form-item>
        </template>
        <template v-if="transitionForm.action === 'close'">
          <el-form-item label="关闭类型" required>
            <el-select v-model="transitionForm.closeType" @change="onCloseTypeChange">
              <el-option label="实施完成" value="implemented" />
              <el-option label="驳回" value="rejected" />
              <el-option label="归档无效" value="archived_invalid" />
            </el-select>
          </el-form-item>
          <template v-if="transitionForm.closeType === 'implemented'">
            <el-form-item label="节能量"><el-input-number v-model="transitionForm.savingValue" :precision="2" :min="0" /></el-form-item>
            <el-form-item label="节能量单位" :required="transitionForm.savingValue !== '' && transitionForm.savingValue != null">
              <el-input v-model="transitionForm.savingUnit" placeholder="例如：m³" />
            </el-form-item>
            <el-form-item label="效果说明"><el-input v-model="transitionForm.effectSummary" type="textarea" :rows="3" /></el-form-item>
            <el-form-item label="附件证据" required><file-upload v-model="transitionAttachmentValue" :limit="5" :drag="false" /></el-form-item>
          </template>
          <template v-else-if="transitionForm.closeType === 'rejected'">
            <el-form-item label="驳回原因" required><el-input v-model="transitionForm.rejectionReason" type="textarea" :rows="3" /></el-form-item>
            <el-form-item label="责任人" required><el-input v-model="transitionForm.responsibleUser" placeholder="请输入责任人账号" /></el-form-item>
          </template>
          <template v-else-if="transitionForm.closeType === 'archived_invalid'">
            <el-form-item label="无效分类" required><el-input v-model="transitionForm.invalidCategory" placeholder="例如：重复建议 / 对象不适用" /></el-form-item>
            <el-form-item label="关闭原因" required><el-input v-model="transitionForm.closeReason" type="textarea" :rows="3" /></el-form-item>
          </template>
        </template>
        <el-form-item label="人工处理备注" required><el-input v-model="transitionForm.remark" type="textarea" :rows="3" /></el-form-item>
        <div v-if="formError" class="inline-error">{{ formError }}</div>
      </el-form>
      <template #footer>
        <el-button @click="transitionVisible = false">取消</el-button>
        <el-button type="primary" :loading="actionLoading === transitionForm.action" @click="submitTransition">确认</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="activityVisible" title="录入人工执行记录" width="560px" append-to-body :close-on-click-modal="false">
      <el-form label-position="top">
        <el-form-item label="执行记录" required><el-input v-model="activityForm.remark" type="textarea" :rows="4" placeholder="记录人工巡检、核验及处理结果" /></el-form-item>
        <el-form-item label="附件"><file-upload v-model="activityAttachmentValue" :limit="5" :drag="false" /></el-form-item>
        <div v-if="activityError" class="inline-error">{{ activityError }}</div>
      </el-form>
      <template #footer>
        <el-button @click="activityVisible = false">取消</el-button>
        <el-button type="primary" :loading="actionLoading === 'addActivity'" @click="submitActivity">保存执行记录</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getErrorStatus as getRequestErrorStatus } from '@/utils/requestError'
import * as echarts from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import {
  addSuggestionActivity,
  generateSuggestionVerification,
  getSuggestionDetail,
  getSuggestionRetrospective,
  getSuggestions,
  transitionSuggestion
} from '@/api/suggestions'
import {
  boardColumnDefinitions,
  buildActivityPayload,
  buildTransitionPayload,
  buildVerificationPayload,
  changeCloseType,
  compactSuggestionQuery,
  currentSourceAlertEventId,
  implementedTargetStatus,
  itemsForColumn,
  priorityBandLabels,
  sortFlows,
  stripOneShotQuery,
  suggestionStatusLabels,
  usesServerFixedVerificationWindow,
  validateTransition,
  verificationStatusLabels,
  writeFailurePolicy
} from '@/views/energy/shared/act4'
import { useChartTheme } from '@/views/energy/shared/cockpitTheme'

echarts.use([LineChart, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer])
defineOptions({ name: 'EnergyAlertSuggestion' })

// 双主题响应式：切顶栏夜览时四维验证对比图即时重绘
const theme = useChartTheme()

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const listError = ref('')
const items = ref([])
const total = ref(0)
const boardCounts = ref({})
const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref(null)
const detailError = ref('')
const detailErrorTitle = ref('详情加载失败')
const writePermissionError = ref('')
const actionLoading = ref('')
const transitionVisible = ref(false)
const formError = ref('')
const verificationPeriod = ref([])
const transitionAttachmentValue = ref('')
const activityVisible = ref(false)
const activityError = ref('')
const activityAttachmentValue = ref('')
const usageChartEl = ref(null)
const costChartEl = ref(null)
const workloadChartEl = ref(null)
let usageChart = null
let costChart = null
let workloadChart = null

const retrospective = ref(null)
const retrospectiveLoading = ref(false)
const retrospectiveError = ref('')
const retrospectiveMonth = ref('')

const filters = reactive({
  status: '', sourceType: '', ruleCode: '', zone: 'ALL', priorityBand: '',
  pageNum: 1, pageSize: 100
})

const transitionForm = reactive(blankTransitionForm())
const activityForm = reactive({ remark: '' })

const statusOptions = [
  { value: 'closed', label: '已关闭（有效 / 无效）' },
  ...Object.entries(suggestionStatusLabels).map(([value, label]) => ({ value, label }))
]
const priorityOptions = Object.entries(priorityBandLabels).map(([value, label]) => ({ value, label }))
const activeSuggestionId = computed(() => detail.value?.suggestion?.suggestionId || route.query.suggestionId || '')
const sourceSnapshot = computed(() => detail.value?.sourceSnapshot || {})
const costSourceDeepLink = computed(() => sourceSnapshot.value.kind === 'costAnomaly' ? sourceSnapshot.value.deepLink : null)
const templateSnapshot = computed(() => detail.value?.templateSnapshot || {})
const sortedFlows = computed(() => sortFlows(detail.value?.flows || []))
const latestVerification = computed(() => detail.value?.latestVerification || null)
const qualityComparison = computed(() => latestVerification.value?.qualityComparison || {})
const sourceObjectLabel = computed(() => {
  const object = sourceSnapshot.value.object || {}
  return object.name || object.code || detail.value?.suggestion?.equipment?.name || detail.value?.suggestion?.area?.name || '全站'
})
const currentSourceEventId = computed(() => currentSourceAlertEventId(detail.value))
const serverFixedVerificationWindow = computed(() => usesServerFixedVerificationWindow(detail.value))

const actionDefinitions = Object.freeze({
  dispatch: { label: '分派人工巡检', primary: true },
  reject: { label: '驳回建议' },
  archiveInvalid: { label: '归档无效' },
  startExecution: { label: '开始人工执行', primary: true },
  startVerification: { label: '进入验证期', primary: true },
  generateVerification: { label: '即点即算验证', primary: true },
  defer: { label: '延期' },
  resume: { label: '恢复延期建议', primary: true },
  close: { label: '三档关闭', primary: true },
  addActivity: { label: '录入人工执行记录', primary: true }
})

const visibleActions = computed(() => (detail.value?.allowedActions || [])
  .filter((action) => actionDefinitions[action])
  .map((action) => ({ value: action, ...actionDefinitions[action] })))

const priorityFactorRows = computed(() => {
  const priority = detail.value?.suggestion?.priority || {}
  const factors = priority.factors || {}
  const weights = priority.weights || {}
  return [
    ['energyScale', '能耗规模'], ['costImpact', '成本影响'], ['duration', '持续时间'],
    ['implementationDifficulty', '实施难度'], ['safetyImpact', '安全影响']
  ].map(([key, label]) => ({ key, label, value: factors[key], weight: weights[key] }))
})

const transitionDialogTitle = computed(() => {
  const action = actionDefinitions[transitionForm.action]
  return action?.label || '建议状态流转'
})

async function loadBoard() {
  loading.value = true
  listError.value = ''
  try {
    const response = await getSuggestions(compactSuggestionQuery(filters))
    const payload = response.data || {}
    items.value = payload.items || []
    total.value = payload.total || 0
    boardCounts.value = payload.boardCounts || {}
  } catch (error) {
    listError.value = errorMessage(error)
  } finally {
    loading.value = false
  }
}

async function loadRetrospective() {
  retrospectiveLoading.value = true
  retrospectiveError.value = ''
  try {
    const response = await getSuggestionRetrospective(compactSuggestionQuery({
      month: retrospectiveMonth.value, zone: filters.zone, ruleCode: filters.ruleCode
    }))
    const payload = response.data || null
    retrospective.value = payload
    if (payload?.filters?.month) retrospectiveMonth.value = payload.filters?.month
  } catch (error) {
    retrospectiveError.value = errorMessage(error)
  } finally {
    retrospectiveLoading.value = false
  }
}

function search() {
  filters.pageNum = 1
  Promise.all([loadBoard(), loadRetrospective()])
}

function resetFilters() {
  Object.assign(filters, { status: '', sourceType: '', ruleCode: '', zone: 'ALL', priorityBand: '', pageNum: 1, pageSize: 100 })
  search()
}

function showDeferred() {
  filters.status = 'deferred'
  filters.pageNum = 1
  loadBoard()
}

function filterByColumn(column) {
  filters.status = column
  filters.pageNum = 1
  loadBoard()
}

async function openDetail(suggestionId, syncQuery = true) {
  if (!suggestionId) return
  detailVisible.value = true
  if (syncQuery && String(route.query.suggestionId || '') !== String(suggestionId)) {
    await router.replace({ query: { ...stripOneShotQuery(route.query), suggestionId } })
  }
  await loadDetail(suggestionId)
}

async function loadDetail(suggestionId) {
  detailLoading.value = true
  detailError.value = ''
  detailErrorTitle.value = '详情加载失败'
  writePermissionError.value = ''
  try {
    const response = await getSuggestionDetail(suggestionId)
    detail.value = response.data
    await nextTick()
    renderVerificationCharts()
    const requestedAction = String(route.query.action || '')
    if (requestedAction && (detail.value?.allowedActions || []).includes(requestedAction)) openAction(requestedAction)
    if (route.query.action || route.query.create) await router.replace({ query: stripOneShotQuery(route.query) })
  } catch (error) {
    handleDetailError(error)
  } finally {
    detailLoading.value = false
  }
}

function handleDetailError(error) {
  const status = getRequestErrorStatus(error)
  detail.value = null
  detailError.value = errorMessage(error)
  if (status === 403) detailErrorTitle.value = '无权查看该建议'
  if (status === 404) {
    detailErrorTitle.value = '建议已不存在'
    detailVisible.value = false
    clearDetailQuery()
  } else if (isTimeout(error)) {
    detailErrorTitle.value = '详情请求超时'
  }
}

function clearDetailQuery() {
  detail.value = null
  detailError.value = ''
  writePermissionError.value = ''
  disposeVerificationCharts()
  if (route.query.suggestionId || route.query.action || route.query.create) {
    const query = stripOneShotQuery(route.query)
    delete query.suggestionId
    router.replace({ query })
  }
}

function openAction(action) {
  if (!(detail.value?.allowedActions || []).includes(action)) return
  if (action === 'generateVerification') {
    generateVerification()
    return
  }
  if (action === 'addActivity') {
    Object.assign(activityForm, { remark: '' })
    activityAttachmentValue.value = ''
    activityError.value = ''
    activityVisible.value = true
    return
  }

  const base = blankTransitionForm()
  base.action = action
  base.rowVersion = detail.value.suggestion?.rowVersion
  const actionTargets = {
    dispatch: 'dispatched', startExecution: 'executing', startVerification: 'verifying',
    defer: 'deferred', resume: detail.value.suggestion?.deferredFromStatus
  }
  base.toStatus = actionTargets[action] || ''
  if (action === 'reject') Object.assign(base, { action: 'close', toStatus: 'invalid_closed', closeType: 'rejected' })
  if (action === 'archiveInvalid') Object.assign(base, { action: 'close', toStatus: 'invalid_closed', closeType: 'archived_invalid' })
  if (action === 'close') {
    const targetStatus = implementedTargetStatus(latestVerification.value)
    if (!targetStatus) {
      ElMessage.error('最新验证尚未形成有效或无效结论，不能实施完成关闭')
      return
    }
    Object.assign(base, { toStatus: targetStatus, closeType: 'implemented' })
  }
  if (!base.toStatus) {
    ElMessage.error('接口未返回可恢复的目标状态')
    return
  }
  Object.assign(transitionForm, base)
  verificationPeriod.value = []
  transitionAttachmentValue.value = ''
  formError.value = ''
  transitionVisible.value = true
}

function onCloseTypeChange(closeType) {
  Object.assign(transitionForm, changeCloseType(transitionForm, closeType))
  transitionForm.toStatus = closeType === 'implemented'
    ? (implementedTargetStatus(latestVerification.value) || '')
    : 'invalid_closed'
  transitionAttachmentValue.value = ''
  formError.value = closeType === 'implemented' && !transitionForm.toStatus
    ? '最新验证尚未形成有效或无效结论，不能实施完成关闭'
    : ''
}

async function submitTransition() {
  if (transitionForm.toStatus === 'verifying' && !serverFixedVerificationWindow.value) {
    transitionForm.verifyStart = verificationPeriod.value?.[0] || ''
    transitionForm.verifyEnd = verificationPeriod.value?.[1] || ''
  }
  if (transitionForm.closeType === 'implemented') {
    transitionForm.attachments = attachmentMetadata(transitionAttachmentValue.value)
  }
  const validationError = validateTransition(transitionForm)
  if (validationError) {
    formError.value = validationError
    return
  }
  formError.value = ''
  const action = transitionForm.action
  actionLoading.value = action
  try {
    await transitionSuggestion(detail.value.suggestion.suggestionId, buildTransitionPayload(transitionForm, {
      includeVerificationWindow: !serverFixedVerificationWindow.value
    }))
    ElMessage.success('建议状态与流转留痕已更新')
    transitionVisible.value = false
    await refreshSuggestionContext()
  } catch (error) {
    await handleWriteError(error, (message) => { formError.value = message })
  } finally {
    actionLoading.value = ''
  }
}

async function submitActivity() {
  if (!activityForm.remark.trim()) {
    activityError.value = '请填写人工执行记录'
    return
  }
  activityError.value = ''
  actionLoading.value = 'addActivity'
  try {
    await addSuggestionActivity(detail.value.suggestion.suggestionId, buildActivityPayload({
      remark: activityForm.remark,
      attachments: attachmentMetadata(activityAttachmentValue.value),
      rowVersion: detail.value.suggestion.rowVersion
    }))
    ElMessage.success('人工执行记录已留痕')
    activityVisible.value = false
    await refreshSuggestionContext()
  } catch (error) {
    await handleWriteError(error, (message) => { activityError.value = message })
  } finally {
    actionLoading.value = ''
  }
}

async function generateVerification() {
  actionLoading.value = 'generateVerification'
  try {
    await generateSuggestionVerification(
      detail.value.suggestion.suggestionId,
      buildVerificationPayload(detail.value.suggestion.rowVersion)
    )
    ElMessage.success('四维验证快照已生成')
    await refreshSuggestionContext()
  } catch (error) {
    await handleWriteError(error, (message) => { detailError.value = message })
  } finally {
    actionLoading.value = ''
  }
}

async function handleWriteError(error, assignMessage) {
  const status = getRequestErrorStatus(error)
  const message = errorMessage(error)
  const policy = writeFailurePolicy(status)
  if (policy.permissionBlocked) {
    writePermissionError.value = message || '当前账号无此业务写权限'
    transitionVisible.value = false
    activityVisible.value = false
    ElMessage.error('当前账号无此业务写权限，操作已拦截')
    return
  }
  assignMessage(message)
  if (policy.refresh) {
    ElMessage.warning('建议状态或版本已变化，已刷新详情与看板')
    await refreshSuggestionContext()
    const refreshedRowVersion = detail.value?.suggestion?.rowVersion
    if (refreshedRowVersion != null) transitionForm.rowVersion = refreshedRowVersion
  } else if (policy.closeDetail) {
    detailVisible.value = false
    clearDetailQuery()
    await loadBoard()
  }
  // 422、409 与超时均不重置表单；超时按钮即为原位重试入口。
}

async function refreshSuggestionContext() {
  const suggestionId = detail.value?.suggestion?.suggestionId || route.query.suggestionId
  await Promise.all([loadBoard(), loadRetrospective(), suggestionId ? loadDetail(suggestionId) : Promise.resolve()])
}

function goSourceAlert() {
  if (!currentSourceEventId.value) return
  router.push({ path: '/energy/alert/alert-list', query: { eventId: currentSourceEventId.value } })
}

function goCostEvidence() {
  if (!costSourceDeepLink.value) return
  router.push(costSourceDeepLink.value)
}

function renderVerificationCharts() {
  disposeVerificationCharts()
  const verification = latestVerification.value
  if (!verification) return
  const muted = verification.status === 'insufficient'
  usageChart = renderComparisonChart(usageChartEl.value, verification.usageComparison, {
    baselineKey: 'usageRate', reportKey: 'usageRate', muted
  })
  costChart = renderComparisonChart(costChartEl.value, verification.costComparison, {
    baselineKey: 'costRate', reportKey: 'costRate', muted
  })
  workloadChart = renderComparisonChart(workloadChartEl.value, verification.workloadComparison, {
    baselineKey: 'workload', reportKey: 'workload', muted
  })
}

function renderComparisonChart(element, comparison = {}, config) {
  if (!element) return null
  const chart = echarts.init(element)
  const chartData = comparisonChartData(comparison, config.baselineKey, config.reportKey)
  const pal = theme.value
  const mutedInk = pal.ink3
  const ink = config.muted ? mutedInk : pal.cyan
  const contrast = config.muted ? mutedInk : pal.amber
  chart.setOption({
    grid: { top: 26, left: 48, right: 18, bottom: 34 },
    tooltip: { trigger: 'axis', backgroundColor: pal.tooltipBg, borderColor: pal.tooltipBorder, textStyle: { color: pal.tooltipInk } },
    legend: { show: chartData.series.length > 1, top: 0, textStyle: { color: pal.ink2 } },
    xAxis: { type: 'category', data: chartData.labels, axisLabel: { color: pal.ink3 }, axisLine: { lineStyle: { color: pal.lineStrong } } },
    yAxis: { type: 'value', axisLabel: { color: pal.ink3 }, splitLine: { lineStyle: { color: pal.splitLine } } },
    series: chartData.series.map((series, index) => ({
      name: series.name, type: 'line', symbol: 'circle', symbolSize: 6, connectNulls: false,
      data: series.data, lineStyle: { width: 2, color: index ? contrast : ink },
      itemStyle: { color: index ? contrast : ink }
    }))
  })
  return chart
}

// 优先消费接口给出的 series；历史快照无日序列时仅展示接口基线/报告聚合值。
function comparisonChartData(comparison = {}, baselineKey, reportKey) {
  const supplied = comparison.series
  if (Array.isArray(supplied) && supplied.length) {
    const labels = supplied.map((point, index) => point.label || point.date || point.time || point.ts || index + 1)
    if (supplied.some((point) => Object.hasOwn(point, 'baseline') || Object.hasOwn(point, 'report'))) {
      return {
        labels,
        series: [
          { name: '基线期', data: supplied.map((point) => point.baseline ?? null) },
          { name: '报告期', data: supplied.map((point) => point.report ?? null) }
        ]
      }
    }
    return { labels, series: [{ name: '接口序列', data: supplied.map((point) => point.value ?? point) }] }
  }
  if (supplied && typeof supplied === 'object') {
    const baseline = Array.isArray(supplied.baseline) ? supplied.baseline : []
    const report = Array.isArray(supplied.report) ? supplied.report : []
    if (baseline.length || report.length) {
      const length = Math.max(baseline.length, report.length)
      return {
        labels: supplied.labels || Array.from({ length }, (_, index) => index + 1),
        series: [{ name: '基线期', data: baseline }, { name: '报告期', data: report }]
      }
    }
  }
  return {
    labels: ['基线期', '报告期'],
    series: [{ name: '接口聚合值', data: [comparison.baseline?.[baselineKey] ?? null, comparison.report?.[reportKey] ?? null] }]
  }
}

function disposeVerificationCharts() {
  usageChart?.dispose()
  costChart?.dispose()
  workloadChart?.dispose()
  usageChart = costChart = workloadChart = null
}

function resizeCharts() {
  usageChart?.resize()
  costChart?.resize()
  workloadChart?.resize()
}

function blankTransitionForm() {
  return {
    action: '', toStatus: '', assignedTo: '', responsibleUser: '', remark: '',
    verifyStart: '', verifyEnd: '', deferReason: '', deferUntil: '', closeType: '',
    savingValue: '', savingUnit: '', effectSummary: '', attachments: [],
    rejectionReason: '', invalidCategory: '', closeReason: '', rowVersion: null
  }
}

function attachmentMetadata(value) {
  return String(value || '').split(',').map((url) => url.trim()).filter(Boolean).map((url) => ({
    name: url.split('/').pop() || url, url, description: '人工执行证据'
  }))
}

function countForColumn(column) {
  return Number(boardCounts.value?.[column] ?? 0)
}

function columnSubtitle(column) {
  return ({ pending: '审核与来源确认', dispatched: '责任对象已明确', executing: '人工执行留痕', verifying: '四维效果核验', closed: '有效 / 无效标签' })[column]
}

function sourceLabel(sourceType) { return sourceType === 'rule' ? '规则触发' : sourceType === 'manual' ? '人工创建' : '—' }
function actionLabel(action) { return actionDefinitions[action]?.label || action || '流转' }
function statusTransitionLabel(flow) {
  const from = suggestionStatusLabels[flow.fromStatus] || flow.fromStatus || '创建'
  const to = suggestionStatusLabels[flow.toStatus] || flow.toStatus || '—'
  return from === to ? to : `${from} → ${to}`
}
function qualityLevel(window) { return window?.level || window?.qualityLevel || '接口快照' }
function shortSignature(value) { return value ? `${String(value).slice(0, 16)}…` : '—' }
function formatDateTime(value) { return value ? String(value).replace('T', ' ').slice(0, 19) : '—' }
function formatPeriod(start, end) { return start || end ? `${start || '—'} → ${end || '—'}` : '未设置' }
function formatScore(value) { return value == null ? '—' : Number(value).toFixed(2) }
function formatWeight(value) { return value == null ? '—' : `${(Number(value) * (Number(value) <= 1 ? 100 : 1)).toFixed(0)}%` }
function formatRate(value) { return value == null ? '—' : `${Number(value).toFixed(2)}%` }
function formatSaving(value, unit) { return value == null ? '—' : `${Number(value).toFixed(2)} ${unit || ''}`.trim() }
function displayValue(value) {
  if (value == null || value === '') return '—'
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}
function errorMessage(error) { return error?.response?.data?.detail || error?.response?.data?.msg || error?.message || String(error || '未知错误') }
function isTimeout(error) { return error?.code === 'ECONNABORTED' || /timeout|超时/i.test(errorMessage(error)) }

// 主题切换即时重绘四维验证快照
watch(theme, () => { if (latestVerification.value) renderVerificationCharts() })

watch(() => route.query.suggestionId, (suggestionId) => {
  if (suggestionId && String(detail.value?.suggestion?.suggestionId || '') !== String(suggestionId)) openDetail(suggestionId, false)
  if (!suggestionId && detailVisible.value) detailVisible.value = false
}, { immediate: true })

onMounted(() => {
  Promise.all([loadBoard(), loadRetrospective()])
  window.addEventListener('resize', resizeCharts)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', resizeCharts)
  disposeVerificationCharts()
})
</script>

<style scoped>
/* 页面布局：主题 token 由 .cockpit-page 提供；.act4-page 只做布局特化 */
.act4-page{
  min-height:calc(100vh - 84px);margin:-16px -16px 0;padding:16px 20px 40px;color:var(--ink);
  background:var(--bg);
  font-family:"PingFang SC",system-ui,sans-serif;font-size:13px;
}
.filter-bar{display:flex;align-items:center;gap:8px;padding:12px 14px;background:var(--panel);border:1px solid var(--line)}
.filter-title{margin-right:auto;display:flex;flex-direction:column}.filter-title b{font-family:var(--serif);font-size:17px;letter-spacing:.08em}.filter-title span,.panel-sub{font:12px var(--mono);color:var(--ink-3);letter-spacing:.08em}
.filter-bar :deep(.el-select){width:116px}.filter-bar :deep(.el-input){width:116px}
/* el-input/select/textarea 皮由 cockpit-tokens 通用块统一供 */
/* 汇总条：数字放大做视觉锚，标签淡出；悬停给出细微反馈，配色沿列语义 */
.summary-strip{display:grid;grid-template-columns:repeat(6,1fr);gap:10px;margin-top:12px}
.summary-cell{border:1px solid var(--line);border-left:2px solid var(--cyan);background:var(--panel);padding:12px 14px;color:var(--ink);display:grid;grid-template-columns:1fr auto;align-items:baseline;text-align:left;cursor:pointer;transition:border-color .18s,background .18s,transform .18s}
.summary-cell:hover{border-color:var(--line-strong);background:color-mix(in srgb, var(--cyan-tint) 55%, var(--panel));transform:translateY(-1px)}
.summary-cell:focus-visible{outline:2px solid var(--cyan);outline-offset:2px}
.summary-cell>span{color:var(--ink-2);font-size:12px;letter-spacing:.02em}
.summary-cell>b{font:28px/1 var(--mono);color:var(--ink)}
.summary-cell>small{grid-column:1/-1;color:var(--ink-3);font-size:12px;margin-top:6px}
.deferred-entry{border-left-color:var(--amber)}
.deferred-entry:hover{background:color-mix(in srgb, var(--amber-tint) 60%, var(--panel))}
/* 看板：五列结构不动（契约）；语义色顶栏区分状态，列头拆两行做主副结构 */
.board{display:grid;grid-template-columns:repeat(5,minmax(200px,1fr));gap:10px;margin-top:12px;align-items:start;overflow-x:auto}
.board-column{position:relative;min-width:200px;border:1px solid var(--line);background:color-mix(in srgb, var(--panel) 70%, transparent);transition:border-color .18s}
.board-column::before{content:"";position:absolute;top:-1px;left:-1px;right:-1px;height:2px;background:var(--line-strong)}
.board-column.col-pending::before{background:var(--violet)}
.board-column.col-dispatched::before{background:var(--cyan)}
.board-column.col-executing::before{background:var(--amber)}
.board-column.col-verifying::before{background:color-mix(in srgb, var(--cyan) 60%, var(--violet))}
.board-column.col-closed::before{background:var(--lime)}
.board-column>header{padding:12px 14px 10px;border-bottom:1px solid var(--line);display:flex;justify-content:space-between;align-items:flex-start;gap:8px}
.board-column>header .col-title{display:flex;flex-direction:column;gap:3px;min-width:0}
.board-column>header .col-title b{font-family:var(--serif);font-size:14px;letter-spacing:.06em;color:var(--ink)}
.board-column>header .col-title small{color:var(--ink-3);font-size:12px;letter-spacing:0}
.board-column>header .col-count{font:20px/1 var(--mono);color:var(--ink-2);padding-top:2px}
.board-column.col-pending>header .col-count{color:var(--violet)}
.board-column.col-dispatched>header .col-count{color:var(--cyan)}
.board-column.col-executing>header .col-count{color:var(--amber)}
.board-column.col-verifying>header .col-count{color:color-mix(in srgb, var(--cyan) 60%, var(--violet))}
.board-column.col-closed>header .col-count{color:var(--lime)}
.card-stack{padding:8px;display:flex;flex-direction:column;gap:8px;min-height:160px}
/* 卡片：优先级作为视觉锚，标题为主，事实清单收窄；hover 用内阴影暗示可点 */
.suggestion-card{width:100%;padding:11px 12px 10px;border:1px solid var(--line);background:var(--panel);color:var(--ink);text-align:left;display:flex;flex-direction:column;gap:8px;cursor:pointer;transition:border-color .16s,transform .16s,box-shadow .16s;position:relative}
.suggestion-card::before{content:"";position:absolute;left:-1px;top:-1px;bottom:-1px;width:2px;background:transparent;transition:background .16s}
.suggestion-card.priority-band-high::before{background:color-mix(in srgb, var(--red) 70%, transparent)}
.suggestion-card.priority-band-medium::before{background:color-mix(in srgb, var(--amber) 70%, transparent)}
.suggestion-card.priority-band-low::before{background:color-mix(in srgb, var(--cyan) 60%, transparent)}
.suggestion-card:hover{border-color:var(--line-strong);transform:translateY(-1px);box-shadow:0 0 0 1px color-mix(in srgb, var(--cyan) 22%, transparent) inset}
.suggestion-card:focus-visible{outline:2px solid var(--cyan);outline-offset:1px}
.card-head{display:flex;justify-content:space-between;align-items:center;gap:8px}
.card-eyebrow{color:var(--ink-3);font-size:12px;letter-spacing:.02em;text-transform:none}
.card-title{font-family:var(--serif);font-size:14px;line-height:1.5;color:var(--ink);letter-spacing:.01em}
.measure-preview{color:var(--ink-2);font-size:12px;line-height:1.55;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}
.card-facts{display:grid;gap:3px;margin:0;padding-top:2px;border-top:1px dashed color-mix(in srgb, var(--line) 80%, transparent)}
.card-facts>div{display:flex;justify-content:space-between;gap:8px;padding-top:3px}
.card-facts dt{color:var(--ink-3);font-size:11px}
.card-facts dd{margin:0;color:var(--ink-2);text-align:right;font-size:11px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;max-width:65%}
.card-footer{display:flex;justify-content:space-between;gap:6px;align-items:center;font-size:12px;padding-top:2px}
.card-footer .status-chip{color:var(--ink-2);padding:1px 6px;border:1px solid var(--line);border-radius:2px;background:var(--panel-2)}
.card-footer .update-time{color:var(--ink-3);font-family:var(--mono)}
.priority-tag,.close-tag,.status-tag{display:inline-flex;align-items:baseline;gap:5px;padding:2px 7px;border:1px solid var(--line-strong);font-size:11px;letter-spacing:.02em;border-radius:2px;background:var(--panel-2)}
.priority-tag em{font-style:normal;font:12px var(--mono);font-weight:600}
.priority-high{color:var(--red);border-color:color-mix(in srgb, var(--red) 48%, transparent);background:var(--red-tint)}
.priority-medium{color:var(--amber);border-color:color-mix(in srgb, var(--amber) 45%, transparent);background:var(--amber-tint)}
.priority-low{color:var(--cyan);border-color:color-mix(in srgb, var(--cyan) 42%, transparent);background:var(--cyan-tint)}
.close-tag{color:var(--lime);border-color:color-mix(in srgb, var(--lime) 40%, transparent);background:var(--lime-tint)}
.column-empty,.empty-state{padding:22px 10px;text-align:center;color:var(--ink-3);border:1px dashed var(--line);font-size:12px}
.panel{background:var(--panel);border:1px solid var(--line);padding:14px 16px}
.deferred-panel,.retrospective-panel{margin-top:12px}
.panel-head{display:flex;align-items:center;border-bottom:1px solid var(--line);padding-bottom:8px;margin-bottom:10px}
.panel-title{font-family:var(--serif);font-size:15px;letter-spacing:.06em}
.count-label{margin-left:auto;color:var(--ink-3);font-size:11px}
.deferred-list{display:grid;grid-template-columns:repeat(3,1fr);gap:10px}
.deferred-card::before{background:var(--amber) !important}
.retrospective-actions{margin-left:auto;display:flex;align-items:center;gap:8px}.retrospective-actions :deep(.el-date-editor){width:132px}.close-type-section{display:grid;grid-template-columns:auto 1fr;align-items:center;gap:12px;margin-bottom:10px}.close-type-section>span{color:var(--ink-2);font-family:var(--serif)}.close-type-counts{display:grid;grid-template-columns:repeat(3,1fr);gap:8px}.close-type-counts div{padding:8px 10px;border:1px solid var(--line);background:var(--panel-2);display:flex;align-items:center;justify-content:space-between}.close-type-counts small{color:var(--ink-3)}.close-type-counts b{font:18px var(--mono)}.retrospective-metrics{display:grid;grid-template-columns:repeat(4,1fr);gap:10px}.retrospective-metrics div,.quality-grid div,.factor-grid div{padding:10px;border:1px solid var(--line);display:flex;flex-direction:column;gap:4px}.retrospective-metrics span,.quality-grid span,.factor-grid span{color:var(--ink-3)}.retrospective-metrics b{font:22px var(--mono)}.hint-list{display:grid;grid-template-columns:repeat(2,1fr);gap:8px;margin-top:10px}.hint-list article{border-left:2px solid var(--cyan);padding:8px 10px;background:var(--panel-2);display:flex;flex-direction:column;gap:4px}.hint-list span{color:var(--ink-2)}.hint-list small{color:var(--ink-3)}.compact-empty{padding:14px;margin-top:10px}.inline-error{padding:9px 10px;border:1px solid color-mix(in srgb, var(--red) 40%, transparent);background:var(--red-tint);color:var(--red);margin:8px 0}.inline-error button{background:none;border:0;color:var(--cyan);cursor:pointer}.page-state{margin-top:12px;padding:16px;border:1px solid var(--line);background:var(--panel);display:flex;align-items:center;gap:12px}.page-state span{color:var(--ink-2);margin-right:auto}.error-state{border-color:color-mix(in srgb, var(--red) 40%, transparent)}.pager{justify-content:flex-end;margin-top:12px}
.drawer-title{display:flex;align-items:center;gap:12px}.drawer-title div{display:flex;flex-direction:column}.drawer-title b{color:var(--ink);font-family:var(--serif);font-size:17px}.drawer-title small{color:var(--ink-3);font:12px var(--mono);margin-top:3px}.drawer-shell{min-height:200px}.detail-body{display:flex;flex-direction:column;gap:12px}.detail-actions{display:flex;gap:8px;flex-wrap:wrap}.permission-state{margin-top:0;border-color:color-mix(in srgb, var(--amber) 50%, transparent)}.permission-state b{color:var(--amber)}.readonly-hint,.snapshot-note,.fixed-window-hint{color:var(--ink-3);font:12px var(--mono);align-self:center}.detail-grid{display:grid;grid-template-columns:1fr 1fr;gap:12px}.facts{display:grid;grid-template-columns:1fr 1fr;gap:10px;margin:0}.facts div{border-bottom:1px solid var(--line);padding-bottom:6px}.facts .wide{grid-column:1/-1}.facts dt{color:var(--ink-3);font:12px var(--mono)}.facts dd{margin:3px 0 0;color:var(--ink);line-height:1.5}.mono-value{font-family:var(--mono)}.responsibility-row{display:grid;grid-template-columns:repeat(4,1fr);gap:8px}.responsibility-row span{padding:8px;background:var(--panel-2);color:var(--ink-3)}.responsibility-row b{display:block;color:var(--ink);margin-top:4px}.factor-grid{display:grid;grid-template-columns:repeat(5,1fr);gap:8px;margin-top:8px}.factor-grid b{font:20px var(--mono)}.factor-grid small{color:var(--ink-3)}.formula-note{margin-top:8px;padding:8px;border:1px dashed var(--line);color:var(--ink-3);font:12px var(--mono);overflow-wrap:anywhere}.timeline-meta{color:var(--ink-3);font-size:11px;margin-top:3px}.timeline-payload{margin-top:5px;padding:6px;background:var(--panel-2);color:var(--ink-3);font:12px var(--mono);overflow-wrap:anywhere}
.quality-warning{padding:10px;border:1px solid color-mix(in srgb, var(--amber) 40%, transparent);background:var(--amber-tint);color:var(--amber)}.verification-meta{display:flex;gap:12px;flex-wrap:wrap;margin:9px 0;color:var(--ink-3);font:12px var(--mono)}.chart-grid{display:grid;grid-template-columns:repeat(3,1fr);gap:10px}
.chart-grid article{border:1px solid var(--line);padding:10px 12px;background:color-mix(in srgb, var(--panel-2) 55%, transparent);display:flex;flex-direction:column;gap:6px}
.chart-grid article>b{font-family:var(--serif);font-size:13px;letter-spacing:.04em;color:var(--ink-2)}
.chart-grid.muted{filter:saturate(.2);opacity:.72}
.comparison-chart{height:210px}.quality-grid{display:grid;grid-template-columns:repeat(3,1fr);gap:8px;margin-top:8px}.quality-grid b{font:16px var(--mono)}.quality-grid small{color:var(--ink-3)}.effect-result{margin-top:8px;padding:10px;border-left:2px solid var(--lime);background:var(--lime-tint);display:flex;justify-content:space-between}.effect-result span{color:var(--ink-2)}.calculation-note{margin-top:8px;padding:9px 10px;border:1px dashed var(--line);display:flex;flex-direction:column;gap:4px}.calculation-note b{font-family:var(--serif)}.calculation-note span{color:var(--ink-2);line-height:1.55}.attachment-list{display:flex;gap:8px;flex-wrap:wrap;margin-top:8px}.attachment-list a{color:var(--cyan)}
@media(max-width:1180px){.summary-strip{grid-template-columns:repeat(3,1fr)}.board{grid-template-columns:repeat(5,220px)}.chart-grid{grid-template-columns:1fr}.comparison-chart{height:180px}}
@media(max-width:800px){.filter-bar{flex-wrap:wrap}.filter-title{width:100%}.panel-head{align-items:flex-start}.retrospective-actions{flex-direction:column;align-items:flex-end}.close-type-section{grid-template-columns:1fr}.detail-grid,.deferred-list,.retrospective-metrics,.responsibility-row,.factor-grid,.quality-grid,.hint-list{grid-template-columns:1fr 1fr}.summary-strip{grid-template-columns:1fr 1fr}}
</style>

<!-- 抽屉皮已迁至 cockpit-tokens.scss 的 .cockpit-modal，本页 <el-drawer class="cockpit-modal" /> 直接消费，页级 <style> 全局皮删除 -->
