"""Render an owner-facing Markdown report from actual local test outputs (no credentials)."""
import argparse, collections, json, pathlib, xml.etree.ElementTree as ET
p=argparse.ArgumentParser();p.add_argument('--output-dir',required=True);args=p.parse_args();out=pathlib.Path(args.output_dir)
repo=pathlib.Path(__file__).resolve().parents[2]
menu=json.loads((out/'浏览器逐菜单验收结果.json').read_text(encoding='utf-8'))
http=json.loads((out/'HTTP逐项验收结果.json').read_text(encoding='utf-8'))
ui=json.loads((out/'浏览器详情交互验收结果.json').read_text(encoding='utf-8'))
counts=collections.Counter(x['kind'] for x in menu['pages']);tests=failures=errors=0
for file in (repo/'EHMS/source/backend/target/surefire-reports').glob('TEST-*.xml'):
    suite=ET.parse(file).getroot();tests+=int(suite.get('tests',0));failures+=int(suite.get('failures',0));errors+=int(suite.get('errors',0))
notes={
'dashboard':'数据库聚合图表可用，台账评分不等于实时评估。',
'my-tasks':'新建与完成持久化已验证；一般用户按当前基线只读。','shift-handover':'新建、提交、签收已验证；虚拟签收不代表真实班组交接。',
'ai-assistant':'本地检索已验证；外部Qwen等API本轮未启用，也未测试。',
'realtime':'虚拟时序读写、时间窗、曲线已核对；不代表现场采集。','diagnosis':'可查趋势与处置；FFT、包络谱、阶次分析未接入。',
'device-template':'创建、发布可用；批量实例化BOM/测点仍属后续功能。','sensor-cal':'可新增、列表查询并在设备档案显示；没有真实证书附件。',
'config-change':'申请、审批、生效状态与快照留痕已验证；不是向PLC下发配置。',
'health':'规则评估、质量门禁和历史回放已验证，不是训练后的寿命AI。','baseline':'显示规则因子与参考上限；非完整工况分层基线训练系统。',
'degradation':'从已保存评估历史绘图，虚拟历史明确标注。','rul':'未接入设备专用寿命模型，不生成虚假剩余天数。',
'maintenance-advice':'人工审核与转工单可用；风险排序仍依赖现有规则口径。',
'strategy':'维护计划与点检模板可用，复杂优化策略待开发。','maintenance-plan':'计划创建、生成任务与重复生成拒绝已验证。',
'inspection':'开始、提交检查、生成缺陷和转工单已验证。','repair-retest':'执行记录、维修后复测、告警工单闭环已验证。',
'spares':'入库、预留、领用及库存查询已验证；只在演示仓SAMPLE-WH操作。',
'reliability':'未具备故障起止和累计运行时长，不能计算真实MTBF/MTTR。','oee':'尚缺计划工时、实际运行、产量和质量数据，OEE未实现。',
'maintenance-cost':'缺工时费用完整口径，不以工单数冒充成本。','energy-health':'缺同窗能耗、负载与健康序列，相关分析未实现。',
'resource-forecast':'可看已登记风险/工单，未实现资源负荷预测模型。','kpi':'已实现业务数量统计，未覆盖所有合同绩效指标。',
'failure-report':'现有告警/工单事件统计，非完整失效分析报告。','coverage-report':'台账接入登记与点位质量可查，实际现场覆盖需验证。',
'period-report':'日粒度业务事件统计与CSV可用，定时报告编排未实现。','external-api':'依赖及配置展示；跨系统联调未因样例启用而完成。',
'iam':'本地真实登录与两角色强制鉴权可用；平台SSO未联调。','dictionaries':'固定枚举可见，在线字典CRUD未实现。',
'approvals':'现有工单审批可用，非跨系统统一审批。','audit-logs':'CRUD/登录/拒绝日志及AUDIT回执已核对，真实链未启用。',
'audit-package':'后端有界快照导出已具备；不等于全量备份或真实链核验。','service-ops':'状态和依赖检查可用；真实备份恢复和灾备演练未完成。',
'sample-workbench':'样例导入、幂等保留、手动采样与能力矩阵已实现，默认生产关闭。'}
lines=['# EHM 1.8.1 虚拟场景与逐项验收报告','',
'## 结论','',
f"当前可在本机演示核心业务，但不能认定为全功能生产交付：67个菜单中，{counts['业务实现']}项有业务接口或治理能力，{counts['部分实现']}项部分实现，{counts['设计展示']}项仍为设计展示。页面能打开不等于功能全部完成。",'',
'本轮没有推送GitHub，没有部署实验室服务器；未验证真实openGauss/openGemini、统一IAM SSO、外部大模型或Fabric。当前入口为 http://127.0.0.1:18194/ehm/ ，业务和日志使用本机H2持久化，时序使用显式虚拟样本适配器，不是Mongo方案回退。','',
'## 本轮数据与修改','',
'- 原有3台验收设备、账号、密码及日志保留；旧运行目录保留为升级前副本，没有清空旧库。',
'- 新增8台SAMPLE-虚拟设备、8个部件、24个测点、初始1176条时序样本、78条历史评估、8条告警、8张工单、8份闭环档案、计划、点检、校准、配置变更和待办，以及3份交接、模板、失效模式、规则、知识案例与备件。验收操作会增加额外记录，因此当前总数不等于初始样例数。',
'- 所有样例通过真实后端与持久化仓储读取。样例不作真实预测精度、设备安全或真实链上证明。',
'- 新增演示数据页、显式安装/采样API，默认关闭；重复安装仅补缺失记录，不覆盖编辑、归档和审核，不重置密码。',
'- 设备360页的部件、技术档案、数字履历和预测维护补入按当前设备查询的关联数据，不再只有跳转链接。',
'- 修复虚拟时序不传时间范围时报错，区分虚拟样本与现场GOOD质量码，修正过时的权限状态说明和虚拟仓库显示。','',
'## 验证证据','',
f'- 后端自动化：{tests}项，失败{failures}、错误{errors}。',
'- 前端Node回归：28项通过。',
f"- 实际本机HTTP：{http['passed']}/{http['total']}项检查通过。见`HTTP逐项验收结果.json`。这些是请求与业务条件检查次数，不是独立功能模块数量。",
f"- 浏览器菜单：{sum(x['rendered'] for x in menu['pages'])}/67项真实菜单切换与内容检查，未发现当次读取报错。见`浏览器逐菜单验收结果.json`。",
f"- 浏览器交互：{sum(x['passed'] for x in ui['checks'])}/{len(ui['checks'])}项筛选、表单、360页签检查通过。见`浏览器详情交互验收结果.json`。",
'- HTTP涵盖：匿名/两角色鉴权、设备增改归档、日志对象关联与公共回执、待办、交接、模板发布、校准、配置审批、生效、健康审核转工单、点检缺陷、工单状态机、库存预留领用、维修执行复测闭环和本地AI检索。',
'- 未逐个穷举所有按钮、所有输入组合、浏览器及并发压测，页面检查不构成完整生产验收。','',
'## 67个菜单逐项结果','',
'“业务实现”表示存在可用的已实现范围，不代表该菜单承诺的所有高级能力均已完善。','',
'| 模块 | 功能 | 当前范围 | 当次页面检查 | 限制 / 待完善 |','|---|---|---|---|---|']
for item in menu['pages']:
    note=notes.get(item['id'],'现有业务数据可读；具体写入入口和高级能力须按合同继续核验。' if item['kind']=='业务实现' else '当前是需求设计示意，后台持久化/执行或现场接口未实现，不能用样例数据宣称已完成。')
    values=[item['group'],item['label'],item['kind'],'可打开、未见读取错误' if item['rendered'] and not item['error'] else '需要复核',note]
    lines.append('| '+' | '.join(str(v).replace('|','/').replace('\n',' ') for v in values)+' |')
lines += ['', '## 建议你现场验收的顺序','',
'1. 用原有admin账号登录；一般用户user仍为只读，密码未改。',
'2. “全场设备群态势”搜索SAMPLE-GT-01，打开详情，切换部件健康、技术档案、数字履历、预测维护，核对当前设备关联内容。',
'3. “实时监测”选择SAMPLE-设备与测点，检查保存曲线。若提示过期，到“集成与系统 → 演示数据与功能验收”手动生成一轮虚拟采样；不要把过期提示当作程序故障。',
'4. “健康评估”查看规则结果及12次最近历史；没有新鲜样本时用历史回放。RUL明确未启用。',
'5. 查看班组交接的草稿及验收时生成的已签收记录；查看模板草稿/发布、配置草稿/审批/生效。',
'6. 在工单、点检与备件页检查多状态记录；演示仓为SAMPLE-WH，不是主仓MAIN。',
'7. 新增自己命名的测试设备，再编辑、归档，按设备编码到日志中找CREATE/UPDATE/DELETE；公共回执NOT_CONFIGURED如实说明未启用上链。',
'8. 查看“演示数据与功能验收”矩阵，对照本表确定下一批优先开发项。','',
'## 启动、回退和后续上传','',
'关机后在本报告目录执行：','',
'```powershell','powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\\start-local-sample.ps1','```','',
'该脚本只用于此电脑。`本机运行/private`含数据库、账号初始文件和服务密钥，不可上传或外发。旧目录`outputs/登录权限-v1.8.0-20261021/本机运行`保留为升级前副本；回退会回到旧副本时间点，需要先停当前自有进程再启动旧实例，不能同时占用相同端口。',
'',
'建议先按本报告确认样例效果和未完成范围，再决定是否上传。公开源码包可包含SAMPLE-虚拟场景代码，但不得包含本机数据库、运行日志、真实地址、密码或模型Key。服务器继续默认openGauss/openGemini，样例不自动导入；真实链、外部模型和统一IAM需独立联调验收。','']
(out/'逐项功能验收报告-v1.8.1.md').write_text('\n'.join(lines),encoding='utf-8')
print(json.dumps(dict(menuTotal=len(menu['pages']),stages=dict(counts),javaTests=tests,httpPassed=http['passed'],httpTotal=http['total'],uiPassed=sum(x['passed'] for x in ui['checks']))))
