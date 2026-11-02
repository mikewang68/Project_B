"""Loopback-only, authenticated acceptance of the opt-in SIM_ operation lab.
No production fixture import, no external delivery, no deletion or account changes.
"""
import argparse, datetime, http.cookiejar, json, pathlib, time, urllib.request, urllib.error, urllib.parse, uuid

p=argparse.ArgumentParser()
p.add_argument('--base',default='http://127.0.0.1:18294/api/ehm/v1')
p.add_argument('--accounts',required=True);p.add_argument('--output',required=True)
p.add_argument('--leave-running',action='store_true')
args=p.parse_args()
if urllib.parse.urlsplit(args.base).hostname not in ('127.0.0.1','localhost'):
    raise SystemExit('Loopback only')
accounts=json.loads(pathlib.Path(args.accounts).read_text(encoding='utf-8'))
results=[];sessions={};confirmation={'confirmation':'SYNTHETIC_ONLY'}
def check(name,value):
    results.append({'name':name,'passed':bool(value)})
    if not value:raise AssertionError(name)
def call(path,method='GET',body=None,role='SUPER_ADMIN',expected=200):
    opener,csrf=sessions[role]
    headers={'Content-Type':'application/json'}
    if csrf:headers['X-EHM-CSRF']=csrf
    req=urllib.request.Request(args.base+path,method=method,headers=headers,data=None if body is None else json.dumps(body).encode())
    try:r=opener.open(req,timeout=60)
    except urllib.error.HTTPError as error:r=error
    with r:
        raw=r.read();data=json.loads(raw) if raw else None
        if r.code!=expected:raise AssertionError(f'{method} {path.split("?")[0]} returned {r.code}, expected {expected}')
        return data
def cmd(name,**extra):return call('/system/simulation/'+name,'POST',{**confirmation,**extra})
def rows(data):return data if isinstance(data,list) else data.get('content',[])
def tick():return cmd('tick',requestId='verify-'+uuid.uuid4().hex)['round']

try:
    for account in accounts:
        role=account['role'];sessions[role]=(urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar())),None)
        result=call('/auth/login','POST',{'username':account['username'],'password':account['password']},role)
        sessions[role]=(sessions[role][0],result['csrfToken'])
    status=call('/system/simulation')
    check('仅在显式启用的SIM隔离项目运行',status['enabled'] and status['projectId'].startswith('SIM_') and not status['externalDelivery'])
    cmd('pause') if status['control']['initialized'] else None
    cmd('install');check('47行来源全部映射为29类虚拟设备',len(status['catalog']['coverage'])==47 and len(status['catalog']['entries'])==29)
    call('/system/simulation/start','POST',confirmation,'USER',403);check('普通用户不能启动模拟器',True)
    call('/system/sample-data/import','POST',{'bundle':'industrial-sample-v1'},expected=409);check('禁止混装旧8台固定场景',True)
    cmd('settings',settings={'intervalSeconds':5,'hoursPerTick':1,'cycleHours':40,'warningRatio':.8,'autoWorkflow':True})
    before=call('/system/simulation')['control']['ticks'];request='verify-dedup-'+uuid.uuid4().hex
    first=cmd('tick',requestId=request);again=cmd('tick',requestId=request)
    check('一轮87个采样真实入库',first['round']['samplesWritten']==87)
    check('重复请求只推进一轮',again['replayed'] and call('/system/simulation')['control']['ticks']==before+1)
    # Exercise communication fault and recovery, regardless of the starting phase.
    while call('/system/simulation')['control']['ticks']%24!=12:tick()
    ecs='SAMPLE-LIVE-EQ-IMG-17'
    quality=call('/data-quality/summary?assetCode='+ecs)
    check('过期源时间使数据质量准入阻断',not quality['healthAssessmentAllowed'])
    while call('/system/simulation')['control']['ticks']%24!=15:tick()
    quality=call('/data-quality/summary?assetCode='+ecs)
    check('恢复有效采样后重新允许评估',quality['healthAssessmentAllowed'])
    asset='SAMPLE-LIVE-EQ-IMG-01';point=asset+'-TEMP'
    reads={
      '设备台账':'/devices?size=200','驾驶舱':'/dashboard/summary','统计图表':'/analytics/overview?days=30',
      '部件BOM':'/assets/'+asset+'/components','测点':'/assets/'+asset+'/measurement-points',
      '采样趋势':'/telemetry/points/'+point+'/samples?limit=2000','健康评估':'/devices/'+asset+'/health-assessments/latest',
      '告警':'/alarms?size=200','工单':'/work-orders?size=200','我的待办':'/my-tasks','班组交接':'/shift-handovers',
      '点检任务':'/inspection-tasks?assetCode='+asset,'校准':'/calibration-records','配置变更':'/configuration-changes',
      '设备模板':'/device-templates','FMECA':'/reliability/failure-modes','知识库':'/reliability/knowledge-cases',
      '备件台账':'/spare-parts?warehouseCode=SAMPLE-LIVE-WH','库存流水':'/spare-stock/transactions',
      '操作日志':'/system/audit-events?size=100'}
    values={}
    for name,path in reads.items():
        values[name]=call(path);check(name+'接口可读且非空',bool(values[name]))
    check('虚拟设备数量为29而非真实台数',len(rows(values['设备台账']))==29)
    check('曲线由带虚拟质量标记的样本绘制',len(values['采样趋势'])>=5 and all(s['quality'].startswith('SYNTHETIC_') for s in values['采样趋势']))
    check('规则评估不伪造AI寿命',values['健康评估']['prediction'].get('expectedDays') is None)
    alarms=rows(values['告警']);orders=rows(values['工单'])
    check('阈值触发告警并完成虚拟闭环',any(a['alarmNo'].startswith(asset) and a['status']=='已关闭' for a in alarms))
    check('工单有已关闭与过程记录',any(o['deviceCode']==asset and o['status']=='已关闭' and len(o['history'])>=4 for o in orders))
    report_status=call('/system/simulation');n=report_status['control']['ticks']
    cmd('start');deadline=time.monotonic()+15
    while time.monotonic()<deadline:
        if call('/system/simulation')['control']['ticks']>n:break
        time.sleep(.25)
    check('不靠浏览器触发的后台定时任务可运行',call('/system/simulation')['control']['ticks']>n)
    cmd('pause');paused=call('/system/simulation')['control']['ticks'];time.sleep(1.2)
    check('暂停后不新增轮次',call('/system/simulation')['control']['ticks']==paused)
    cmd('settings',settings={'intervalSeconds':300,'hoursPerTick':300/3600,'cycleHours':40,'warningRatio':.8,'autoWorkflow':False})
    if args.leave_running:cmd('start')
except Exception as error:
    results.append({'name':'验收执行异常','passed':False,'error':str(error)})
    try:cmd('pause')
    except Exception:pass
finally:
    report={'generatedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'base':args.base,
            'synthetic':True,'serverDeployed':False,'publicChainDelivery':False,'results':results,
            'passed':sum(r['passed'] for r in results),'failed':sum(not r['passed'] for r in results)}
    out=pathlib.Path(args.output);out.parent.mkdir(parents=True,exist_ok=True);out.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({'passed':report['passed'],'failed':report['failed'],'report':str(out)},ensure_ascii=False))
raise SystemExit(bool(report['failed']))
