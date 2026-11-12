-- 005: ai_event 活动行部分索引。
-- 背景：28 万行 CANCELLED 历史行（防删触发器禁止物理清理）使
-- `WHERE status_code <> 'CANCELLED'` 的 COUNT 与分页查询退化为全表扫描（~300-600ms）。
-- 部分索引只索引活动行（当前 ~7 行），COUNT 与 ORDER BY occurred_at DESC 分页均走 index-only。
-- 幂等：IF NOT EXISTS；对既有功能零影响（纯索引，不改数据/语义）。
CREATE INDEX IF NOT EXISTS idx_ai_event_active_occurred
    ON safety.ai_event (occurred_at DESC, id DESC)
    WHERE status_code <> 'CANCELLED';

-- 待复核计数（counts()/countPendingReview）同样命中该部分索引。
ANALYZE safety.ai_event;
