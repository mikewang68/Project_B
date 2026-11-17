"""Build separate human-readable audit reports, without editing the source workbook or system."""
import collections,hashlib,json,pathlib,re,xml.etree.ElementTree as ET
ROOT=pathlib.Path(__file__).resolve().parents[1];LOCAL=ROOT/'.local/requirements-audit-20261116'
OUT=ROOT.parents[1]/'outputs/EHMS-需求功能逐项核查-20261116'
source=json.loads((LOCAL/'source.json').read_text(encoding='utf-8'))
review=json.loads((LOCAL/'review.json').read_text(encoding='utf-8'))
own=[r for r in source['relationships'] if r['A']=='设备健康管理系统']
ids={r['E'] for r in own}
assert ids==set(review['functions']) and len(ids)==20
assert {r['C'] for r in own}==set(review['requirements']) and len(own)==23
assert hashlib.sha256(pathlib.Path(source['source']).read_bytes()).hexdigest()==source['sha256']

def cell(r,letter):return f"02需求与功能说明!{letter}{r}"
def escape(v):return str(v).replace('|','／').replace('\n',' ')
def evidence(entries):
 result=[]
 for file,anchor in entries:
  path=ROOT/file;lines=path.read_text(encoding='utf-8').splitlines()
  matches=[i+1 for i,line in enumerate(lines) if anchor in line]
  if not matches:raise ValueError('EVIDENCE_ANCHOR_NOT_FOUND '+file+' '+anchor)
  label=file.replace('.local/requirements-audit-20261116/deployed-source/','已部署worker源码/')
  result.append(f'`{label}:{matches[0]}`')
 return '；'.join(result)
for f,r in review['functions'].items():r['resolvedEvidence']=evidence(r['evidence'])

counts=collections.Counter(x['status'] for x in review['functions'].values())
totals=dict(tests=0,failures=0,errors=0,skipped=0)
for p in (ROOT/'source/backend/target/surefire-reports').glob('TEST-*.xml'):
 root=ET.parse(p).getroot()
 for k in totals:totals[k]+=int(root.attrib.get(k,0))

lines=['# EHMS 需求与功能逐项核查报告','',
'核查日期：2026-11-16。负责人范围：闫鑫钰负责的设备健康管理系统。',
'','## 一、结论','',
'**现有系统有真实后端、持久化和若干业务闭环基础，但不能据此确认已满足这份工作簿的全部 EHMS 需求。8 条业务需求目前均不宜整条签收。**',
'',
'核对范围为 8 条 R-E 需求、23 条 EHMS 需求—功能关系、20 个去重 F-E 功能；另有 R-E08 复用的 2 条 DTS 功能关系。F-E02 服务 3 条需求，F-E13 服务 2 条需求，未重复计算为新功能。原表没有分配 F-E06/F-E07，本报告不自行补编号。',
'',
'逐功能结论：'+ '；'.join(f'{status} {n} 项' for status,n in counts.items())+'。这不是生产验收通过率。已实现一项机制，不代表设备覆盖、页面签收或跨系统验收也完成。',
'',
'优先处理的已确认问题：','',
'1. 历史曲线会把 BAD 的有限数值作为普通曲线点；null 被删除后，前后样本直接连线。MQ-SIM 的来源标签仍是 openGemini。',
'2. 维修页 `advanceCaseOrder` 没有登记在中央前端动作表；即使拥有全部相关菜单动作，也会被拒绝。不是单纯让 IAM 再多授权就能解决。',
'3. CaseFlow 路径有复测闭环门槛，但通用工单状态路径允许“待复测→已关闭”，没有同等证据门槛。领域模型探针已复现，不表示生产库已被测试写入。',
'4. 告警/诊断/审核/维修业务记录的 operator、reviewer 仍取自客户端字段，可能缺省为 Demo 身份。服务器审计日志有真实身份，并不能自动修正这些业务字段。',
'5. 到期计算、待办、工单、执行记录都存在，不等于“到期提醒→去重待办→分配执行→结果回填”已经贯通。DTS 健康摘要也尚未形成正式跨系统契约。',
'','## 二、核查依据与限制','',
'- 原始工作簿：`系统需求功能工作簿.xlsx`，工作簿内版本 v2.0 / 2026-11-14。已读取两个表、单元格引用公式、超链接和共享功能主要定义位置，未保存或改写原文件。',
'- 原表“有实现线索”“服务在线”仅作为来件说明，不当作已通过验收；表内内容为需求草案，不擅自视为甲方最终签字范围。',
'- 当前前端指向 `EHMS-business-workspace-20261116-r1/ehms`；EHMS 后端仍为 `EHMS-public-data-20261113-r1/ehm-service.jar`。本次只读检查 EHMS、公共消费服务、消费定时器均 active，后端 health 为 UP。',
'- 比对了实际运行 JAR 内的 13 个关键类：资料服务、维护计划/点检、工单领域、CaseFlow、健康审核领域/控制器、告警领域、维护事务切面等 11 个与本地编译类摘要一致。TelemetryController 和健康评估服务属于 11/13 专用补丁，另取部署目录中的对应源码核对，未用旧本地类替代判断。',
'- 已读取已部署独立 worker 的接收箱、业务映射、MQ transport 源码及非私密历史验收资料。11/13 的 4 条 HTTP + 1 条 MQ 成功记录属于历史单侧证据；本次没有重新写生产库、重投公共消息、重置消费位点、改账号或重启服务。未读取私密数据库/MQ/HTTP口令。',
f"- 本次本地 Maven 回归：{totals['tests']} 项，失败 {totals['failures']}、错误 {totals['errors']}、跳过 {totals['skipped']}。旧回归全通过仍未覆盖本报告新增的需求缺口。另运行了隔离渲染、纯权限策略及 Java 领域模型探针。",
'- 线上本人登录后的页面写操作、真实 openGauss 全流程、DTS 两端页面签收均未在本次执行，不能用服务健康或历史测试代替。',
'- 本次为核查，不是修复任务；未改动产品业务代码、服务器发布版本、原始工作簿或共享在线文档。',
'','### 状态口径','',
'“已实现（限定分配范围）”表示相应机制在限定对象内具备代码与技术证据，不是整条需求签收；“部分实现”表示有可复用能力但规则、数据、交互或链路仍缺项；“未实现（正式跨系统契约）”不代表毫无基础数据，只表示所要求的正式对接能力尚未形成。P0 为正确性、可信留痕或关键流程阻断优先项，P1 为完整性/联调项。',
'','## 三、8 条业务需求汇总','',
'| 需求 | 原表业务需求 | 对应功能（含复用） | 当前结论 | 主要差距 |','| --- | --- | --- | --- | --- |']
for req in sorted(review['requirements']):
 rows=[r for r in own if r['C']==req];r=review['requirements'][req]
 lines.append('| '+' | '.join(map(escape,[req,rows[0]['D'],'、'.join(x['E'] for x in rows),r['status'],r['summary']]))+' |')
lines+=['','## 四、20 个功能逐条核查','']
for f in sorted(ids,key=lambda s:int(s[3:])):
 r=review['functions'][f];definition=source['functions'][f];relations=[x for x in own if x['E']==f]
 lines += [f"### {f} {definition['cells']['B']}",'',
 f"结论：**{r['status']}**。优先级：{r['priority']}。",'',
 f"原表位置：表一第 { '、'.join(str(x['row']) for x in relations)} 行；主要定义 `{cell(definition['row'],'A')}`；原“当前情况”在 `{cell(definition['row'],'F')}`。支撑需求：{'、'.join(x['C'] for x in relations)}。",'',
 '**当前已有**：'+r['implemented'],'',
 '**尚缺或不符合之处**：'+r['gap'],'',
 '**页面/接口**：'+r['entry'],'',
 '**建议补测/完成判据**：'+r['acceptance'],'',
 '**代码证据**：'+r['resolvedEvidence'],'']
lines+=['## 五、多对多关系逐行核对','',
'下列 23 行与原表 EHMS 行逐一对应。状态沿用主要功能定义，复用不会产生一份新的实现。','',
'| 表一行号 | 需求 | 功能 | 在本需求中承担什么 | 实现情况 | 表二主要定义 |','| --- | --- | --- | --- | --- | --- |']
for r in own:
 f=r['E'];lines.append('| '+' | '.join(map(escape,[r['row'],r['C'],f,r['G'],review['functions'][f]['status'],cell(source['functions'][f]['row'],'A')]))+' |')
lines+=['','### R-E08 的 DTS 协作关系','',
'| 表一行号 | 共享功能 | 定义位置 | 责任与本次判断 |','| --- | --- | --- | --- |']
for r in source['relationships']:
 if r['A']=='设备健康管理系统':continue
 f=r['E'];d=source['functions'][f]
 lines.append(f"| {r['row']} | {f} {d['cells']['B']} | {cell(d['row'],'A')} | DTS负责设备映射/展示或详情联查；EHMS提供权威数据及受权详情入口。本次未核验DTS现网，不认定其已完成或未完成。 |")
lines+=['','## 六、覆盖范围与数据真实性','',
'| 对象 | 已知范围 | 不能混同的结论 |','| --- | --- | --- |',
'| 普通/SAMPLE-LIVE设备 | 原后台模拟与原业务路径保留 | “每天有新数据”不证明厂商真实接入，也不证明全部新流程已实现 |',
'| 公共 MQ-SIM | 3台桥式起重机和1台提升机，独立工程模拟编码 | 不是全部现场设备，也不是厂家认证的BOM |',
'| 需求点行数 | 572 行 | 不是已经全部入库或全部有时序曲线 |',
'| 公共目录相交点 | 413 点，3 点待映射，可映射 410 点 | 当前目录之外还有159行；不能把413点相交称为572行全覆盖 |',
'| 标量点 | 203 个；字符串、枚举、数组/引用有无损原始观测保存路径 | 不能把410个映射点全部称为可画数值趋势的标量点 |',
'| 缺失映射 | 三台桥机各一个 EHM-PT-0207 | 厂家、业务与公共目录责任方补正式含义/映射，不猜编号或极性 |',
'| 健康评估 | 规则基线；MQ-SIM无有效上限时拒绝用于评分 | 未经标定的规则提示不是模型预测精度；RUL未启用本身不等于违反本工作簿明确允许的边界 |',
'','## 七、建议修复与联调顺序','',
'1. 先修正确性和流程阻断：坏质量/缺值曲线、MQ来源标识、CaseFlow动作名映射、所有维修关单入口的统一门槛。',
'2. 收紧可信留痕：operator/reviewer从服务端会话取值；关闭原因必填；复测点归属、本次复测清单和页面允许关闭条件一致。',
'3. 补维护提醒闭环：明确业务周期/累计读数来源，建立维护依据ID、周期实例、去重待办、执行单与结果回填关系，明确停用/延期处理。',
'4. 补证据和来源：原event_id、模拟标记、接收时间贯穿历史接口与页面；绑定异常前后窗口/证据时点；追加意见与修订关系分别表示。',
'5. 核查健康转单事务、并发幂等、过期评估；统一普通点与MQ点的无阈值评分策略。',
'6. 最后做跨系统和真实资料签收：补三处映射及厂家参数，与雷文冻结并验证DTS契约。',
'','### 需要业务/其他负责人集中确认的事项','',
'- 设备/厂家/公共目录负责人：统一设备编号、MQ-SIM与现场映射、三处EHM-PT-0207、单位/极性、正式阈值及资料版本。',
'- 甲方设备专业：质量告警与物理故障分类、持续异常合并/恢复策略；日历还是运行时长、停机是否计时、提醒责任与延期规则；必测点和人工合格判据；哪些工单确需复测。',
'- IAM/雷文：计划、执行、审核/复核的真实角色分工及单一项目区域权限。修复前端动作名是EHMS自己的工作，不应把所有按钮问题都转给IAM。',
'- 雷文/DTS：逐设备摘要字段、更新时效/过期原因、设备/区域映射、服务访问身份与详情路由；分别负责哪端签收。',
'- 董博涵/公共接入：本次已核worker可靠接收，不需重新索要整套密钥；后续只补目录缺项、授权测试事件及明确时间窗。不要为页面验证重置共享组或启动未授权生成器。',
'','## 八、交给董博涵的简要结论','',
'> 我已按工作簿v2.0核对EHMS的8条需求、23条关系和20个独立功能。现有代码具备设备资料、公共可靠接入、告警处置、维护点检、诊断/复测和规则审核基础；不能按整项需求全部签收。11/13公共HTTP/MQ落库证据仍有效，16日前端整理没有覆盖该后端。本轮确认了坏质量/缺值趋势、维修动作授权映射、通用关单门槛和业务操作人来源等具体缺口，将先由EHMS修复，再补维护待办闭环和证据来源联查。真实设备资料、三处测点映射、维护/复核规则和DTS摘要契约需要集中确认。当前结论为源码及隔离测试核查，未冒充本人线上操作或两端联调签收。',
'','## 九、核查留档','',
f"原文件 SHA-256：`{source['sha256']}`。原文件核查前后摘要一致。",
'',
'隔离探针结果：`visual-probe-results.json` 显示历史图普通曲线输入包含BAD值999，删除null后剩余[1,999,4]，MQ来源为openGemini；纯策略探针显示全部菜单动作授权时advanceWork允许、advanceCaseOrder拒绝；Java领域探针显示通用状态路径在没有Case/复测实体的情况下可以到CLOSED。',
'',
'完整源数据关联、关键类摘要、部署worker源码快照及探针保存在本地 `.local/requirements-audit-20261116/`，不含读取到的私密配置。报告中“已部署worker源码”均指此只读快照。后续修复应保留公共接入补丁，不能用旧本地文件整体覆盖线上。','']

feedback=['# EHMS 工作簿建议回填内容','',
'核对人：闫鑫钰。核对日期：2026-11-16。以下是可复制的核查结论，不是已直接修改共享文档。',
'',
'**请优先回填表二的主要定义和“反馈与修订”区域，不要覆盖表一 I 列引用公式。** 表一多行引用同一功能时，会随主要定义的“当前情况”同步；同一功能只维护一份核查状态。','',
'## 1. 需求级反馈','']
for req in sorted(review['requirements']):
 b=source['requirements'][req];target=next(x['row'] for x in b['rows'] if x['cells'].get('A')=='反馈与修订')
 feedback += [f"### {req} — 填入 {cell(target,'B')}",'', '核对人：闫鑫钰；日期：2026-11-16。'+review['requirements'][req]['feedback']+' 当前结论：'+review['requirements'][req]['status']+'。本次未执行生产业务写入，原账号页面与跨系统验收另行签收。','']
feedback += ['## 2. 功能级“当前情况”','',
'以下只给主要定义的 F 列建议；较长差距见完整报告。','',
'| 功能 | 回填单元格 | 建议内容 |','| --- | --- | --- |']
for f in sorted(ids,key=lambda s:int(s[3:])):
 r=review['functions'][f];d=source['functions'][f]
 content=r['status']+'。'+r['implemented']+' 待补：'+r['gap']+' 本轮为源码/隔离核查，非生产全流程签收。'
 feedback.append('| '+' | '.join(map(escape,[f,cell(d['row'],'F'),content]))+' |')
feedback += ['','R-E08涉及的F-D03、F-D05由雷文/DTS端核对自己的主要定义，本次只提供EHMS端对接需求，不代其填写已完成。','']
OUT.mkdir(parents=True,exist_ok=True)
(OUT/'EHMS需求功能逐条核查报告.md').write_text('\n'.join(lines),encoding='utf-8')
(OUT/'给董博涵的工作簿回填建议.md').write_text('\n'.join(feedback),encoding='utf-8')
assert sum(1 for line in lines if re.match(r'### F-E\d+ ',line))==20
(LOCAL/'audit-verification.json').write_text(json.dumps({'requirements':8,'ownRelations':23,'ownFunctions':20,'sharedRelations':2,'statusCounts':dict(counts),'maven':totals,'sourceUnchanged':True,'productionBusinessWrites':False,'productCodeModified':False,'reports':[str(OUT/'EHMS需求功能逐条核查报告.md'),str(OUT/'给董博涵的工作簿回填建议.md')]},ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({'reports':str(OUT),'functions':20,'relationships':23,'statusCounts':dict(counts),'sourceUnchanged':True},ensure_ascii=False))
