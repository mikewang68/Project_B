# SCS 监控接入与告警配置（D-2）

指标源：SCS 后端 `/actuator/prometheus`（免鉴权，SecurityInterceptor 白名单放行 `/actuator`）。
经 P1 阶段增强后除 JVM/HikariCP/HTTP 通用指标外，还输出 **SCS 业务指标**：

| 指标 | 含义 | 告警建议 |
|---|---|---|
| `scs_alerts` | 告警总量 | 趋势观测 |
| `scs_alerts_open` | 未关闭告警数 | 持续 >0 观察；突增结合业务判断 |
| `scs_alerts_processing` | 处置中告警数 | 长期高位=处置力量不足 |
| `scs_alerts_closed` | 已关闭告警数 | 趋势观测 |
| `scs_edge_pending_events` | 补传队列未完成深度（PENDING/SYNCING/FAILED，不含已补传） | **持续增长 = 补传链路异常**（核心信号） |

## 方式一：Prometheus 抓取

```yaml
scrape_configs:
  - job_name: scs-backend
    scrape_interval: 15s
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ['192.168.101.57:18082']
        labels: { service: scs, env: bpoc }
```

告警规则：将 `alert-rules.yaml` 内容并入 Prometheus rule_files（或拷贝到
`/etc/prometheus/rules/scs.yml` 后 reload）。

## 方式二：Nightingale（夜莺，node1 :17000 已有部署）

1. 夜莺侧"数据源"接入 Prometheus（或 VictoriaMetrics）抓取上述 job；
   categraf 亦可直接 `inputs.prometheus` 地址抓取 `http://192.168.101.57:18082/actuator/prometheus`。
2. 告警规则在夜盏界面按 `alert-rules.yaml` 中的 promql 与阈值逐条录入（夜莺兼容 promql）。
3. 通知策略建议：`SCSEdgeReplayBacklog` 与 `SCSBackendDown` 走电话/短信通道，其余走 IM。

## 看板

`grafana-scs-overview.json` 可直接在 Grafana → Dashboards → Import 导入
（数据源选择任意 Prometheus 兼容源）。包含三个 ROW：

1. **安全态势**：告警四态即时值 + 边缘补传队列深度（业务指标）；
2. **服务健康**：HTTP QPS/5xx 率、Tomcat 线程、HikariCP 连接池（活跃/等待/获取耗时）；
3. **JVM 与系统**：堆内存、GC 暂停、CPU、磁盘余量。

## 告警规则摘要（alert-rules.yaml）

| 规则 | 表达式要点 | 级别 |
|---|---|---|
| SCSBackendDown | `up{job="scs-backend"} == 0` 持续 2m | P0 电话 |
| SCSEdgeReplayBacklog | `scs_edge_pending_events` 5m 内增长 >10 | P0 电话 |
| SCSHigh5xxRate | 5xx QPS > 0.1 持续 5m | P1 IM |
| SCSJvmHeapHigh | 堆使用率 > 85% 持续 10m | P1 IM |
| SCSHikariPoolSaturated | `hikaricp_connections_pending > 0` 持续 2m | P1 IM |
| SCSDiskNearlyFull | 磁盘剩余 < 15% | P1 IM |
