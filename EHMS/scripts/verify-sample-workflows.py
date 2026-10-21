"""Explicit local acceptance test. Only runs on loopback with a supplied private fixture account file.
Creates/archives its own SAMPLE-VERIFY records; never resets accounts or existing records.
"""
import argparse, datetime, http.cookiejar, json, pathlib, time, urllib.request, urllib.error, urllib.parse
p=argparse.ArgumentParser();p.add_argument('--accounts',required=True);p.add_argument('--output',required=True);p.add_argument('--base',default='http://127.0.0.1:18194/api/ehm/v1');args=p.parse_args()
if urllib.parse.urlsplit(args.base).hostname not in ('127.0.0.1','localhost'):raise SystemExit('Only loopback acceptance environments are allowed')
accounts=json.loads(pathlib.Path(args.accounts).read_text(encoding='utf-8'));results=[];sessions={};stamp=str(int(time.time()));bundle={'bundle':'industrial-sample-v1'}
def client():return urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
def call(name,path,method='GET',body=None,expected=200,role='SUPER_ADMIN',predicate=None):
    conn,csrf=sessions.get(role,(client(),None));headers={'Content-Type':'application/json'}
    if csrf:headers['X-EHM-CSRF']=csrf
    request=urllib.request.Request(args.base+path,method=method,headers=headers,data=None if body is None else json.dumps(body).encode())
    try:
        try:response=conn.open(request,timeout=30)
        except urllib.error.HTTPError as e:response=e
        with response:
            raw=response.read()
            try:data=json.loads(raw) if raw else None
            except ValueError:data=None
            ok=response.code==expected and (predicate(data) if predicate and response.code==expected else True)
            results.append(dict(name=name,path=path.split('?')[0],method=method,status=response.code,expected=expected,passed=ok,
                error=(data.get('message',data.get('detail','')) if isinstance(data,dict) else '') if not ok else ''))
            return data
    except Exception as e:results.append(dict(name=name,path=path.split('?')[0],method=method,passed=False,error=type(e).__name__));return None
def rows(d):return d if isinstance(d,list) else (d or {}).get('content',[])
def assert_check(name,value):results.append(dict(name=name,passed=bool(value)))
try:
    for account in accounts:
        conn=client();sessions[account['role']]=(conn,None)
        d=call('真实账号登录 '+account['role'],'/auth/login','POST',dict(username=account['username'],password=account['password']),role=account['role'])
        if not d or 'csrfToken' not in d:raise SystemExit('Cannot authenticate private acceptance account; no passwords were changed')
        sessions[account['role']]=(conn,d['csrfToken'])
    call('普通用户不可导入样例','/system/sample-data/import','POST',bundle,403,'USER')
    call('安装虚拟工业场景','/system/sample-data/import','POST',bundle)
    call('重复导入不覆盖数据','/system/sample-data/import','POST',bundle,predicate=lambda d:d['totalInserted']==0)
    call('手动生成虚拟采样','/system/sample-data/tick','POST',bundle,predicate=lambda d:d['samplesCreated']>=18)
    asset='SAMPLE-GT-01';point=asset+'-VIB';comp=asset+'-DRIVE'
    reads=[('设备台账','/devices'),('设备档案','/devices/'+asset),('驾驶舱','/dashboard/summary'),('图表统计','/analytics/overview?days=30'),
        ('部件BOM','/assets/'+asset+'/components'),('测点管理','/assets/'+asset+'/measurement-points'),('数据质量','/data-quality/points?assetCode='+asset),
        ('数据质量汇总','/data-quality/summary?assetCode='+asset),('告警中心','/alarms'),('工单中心','/work-orders'),('待办','/my-tasks'),('班组交接','/shift-handovers'),
        ('设备模板','/device-templates'),('计量校准','/calibration-records'),('配置变更','/configuration-changes'),('FMECA','/reliability/failure-modes'),
        ('故障编码','/reliability/fault-codes'),('告警规则','/reliability/alarm-rules'),('知识库','/reliability/knowledge-cases'),('SLA','/reliability/sla-policies'),
        ('健康评估','/devices/'+asset+'/health-assessments/latest'),('评估历史','/devices/'+asset+'/health-assessments?limit=20'),
        ('维保计划','/maintenance-plans?assetCode='+asset),('点检任务','/inspection-tasks?assetCode='+asset),('缺陷清单','/maintenance-defects?assetCode='+asset),
        ('备件台账','/spare-parts?warehouseCode=SAMPLE-WH'),('库存汇总','/spare-stock/summary?warehouseCode=SAMPLE-WH'),('库存预警','/spare-stock/alerts?warehouseCode=SAMPLE-WH'),
        ('库存流水','/spare-stock/transactions'),('预留记录','/spare-reservations'),('维修复测档案','/work-orders/SAMPLE-WO-TS-03/case'),
        ('接口能力','/system/capabilities'),('交付状态','/system/delivery-readiness'),('系统状态','/system/status'),('AI配置状态','/assistant/status'),('账号列表','/auth/users'),
        ('日志查询','/system/audit-events'),('后台任务日志','/system/audit-tasks')]
    for name,path in reads:call(name+'可读取',path)
    call('持久化虚拟时序可读取','/telemetry/points/'+point+'/samples?limit=2000',predicate=lambda d:len(d)>=48 and all(x['quality'].startswith('SYNTHETIC_') for x in d))
    code='SAMPLE-VERIFY-'+stamp;payload=dict(code=code,name='[虚拟]自动验收设备',type='测试设备',area='演示验收区')
    call('管理员新增设备','/devices','POST',payload,201)
    payload['name']='[虚拟]自动验收设备-已修改';call('管理员编辑设备','/devices/'+code,'PUT',payload)
    call('普通用户查询设备','/devices/'+code,role='USER')
    call('普通用户不能新增','/devices','POST',dict(code=code+'-DENIED',name='Forbidden'),403,'USER')
    call('普通用户不能修改','/devices/'+code,'PUT',payload,403,'USER')
    call('普通用户不能删除','/devices/'+code,'DELETE',expected=403,role='USER')
    call('管理员归档本次临时设备','/devices/'+code,'DELETE',expected=204)
    d=call('真实操作日志按动作查询','/system/audit-events?action=CREATE&size=100');events=[x for x in (d or {}).get('items',[]) if x.get('objectId')==code]
    assert_check('新增设备日志包含对象编码和成功结果',any(x['outcome']=='SUCCESS' for x in events))
    if events:
        event=events[0]['eventId'];receipt=None
        for attempt in range(8):
            receipt=call('公共日志回执核查','/system/audit-events/'+event+'/receipt')
            if receipt and receipt.get('lookupState')=='VERIFIED_PUBLIC_RECEIPT':break
            time.sleep(.5)
        assert_check('公共日志已保存但未冒充上链成功',receipt and receipt.get('lookupState')=='VERIFIED_PUBLIC_RECEIPT' and receipt.get('chainState')=='NOT_CONFIGURED')
    task=call('新增待办','/my-tasks','POST',dict(title='[虚拟]接口验收待办',assetCode=asset,taskType='人工核查',sourceType='MANUAL',assignee='演示工程师'),201)
    if task:call('待办完成持久化','/my-tasks/'+task['taskNo']+'/complete','POST',dict(operator='演示验收员'))
    ho=call('新增交接草稿','/shift-handovers','POST',dict(summary='[虚拟]自动验收交接',shiftDate=datetime.date.today().isoformat(),outgoingShift='白班',incomingShift='夜班',outgoingLeader='演示白班长',incomingLeader='演示夜班长'),201)
    if ho:
        path='/shift-handovers/'+ho['handoverNo'];call('交接提交','{}{}'.format(path,'/submit'),'POST',dict(operator='演示白班长'));call('交接签收',path+'/receive','POST',dict(operator='演示夜班长'))
    tpl='SAMPLE-VERIFY-TPL-'+stamp
    d=call('新建模板','/device-templates','POST',dict(templateCode=tpl,name='[虚拟]验收模板',deviceType='门吊',components=[dict(componentCode='DRIVE',name='驱动',category='传动',quantity=1,critical=True)],measurementPoints=[]),201)
    if d:call('模板发布','/device-templates/'+tpl+'/publish','POST',dict(operator='演示主管'))
    call('新增校准记录','/calibration-records','POST',dict(assetCode=asset,componentCode=comp,pointCode=point,sensorCode='SAMPLE-VERIFY-SENSOR',calibrationType='虚拟校准',beforeValue=5.2,afterValue=5.0,tolerance=.3,unit='mm/s',result='PASS',remark='虚拟，无证书附件'),201)
    change=call('新增配置变更','/configuration-changes','POST',dict(assetCode=asset,objectType='MEASUREMENT_POINT',objectCode=point,changeType='虚拟变更',beforeSnapshot={'intervalSeconds':300},afterSnapshot={'intervalSeconds':120},reason='虚拟接口验收'),201)
    if change:
        path='/configuration-changes/'+change['changeNo'];call('变更未审批不能生效',path+'/apply','POST',{},409);call('配置变更审批',path+'/approve','POST',dict(operator='演示主管'));call('配置变更生效履历',path+'/apply','POST',dict(operator='演示执行员'))
    health=call('运行规则健康评估','/devices/'+asset+'/health-assessments/run','POST',expected=201,predicate=lambda d:d['prediction'].get('expectedDays') is None)
    if health:
        path='/health-assessments/'+health['assessmentId'];call('未审核不可转工单',path+'/work-order','POST',{},409);call('健康建议审核',path+'/review','POST',dict(decision='ACCEPTED',comment='虚拟验收，人工核查后处理',reviewer='演示主管'));call('健康建议转待审批工单',path+'/work-order','POST',dict(title='[虚拟]健康规则复核'))
    call('历史样本回放','/devices/'+asset+'/health-assessments/replay','POST',expected=201)
    call('已有活动点检任务阻止重复生成','/maintenance-plans/SAMPLE-PLAN-GT-01/inspection-tasks','POST',{},409)
    newplan=call('新增点检计划','/maintenance-plans','POST',dict(assetCode=asset,componentCode=comp,name='[虚拟]接口验收计划',cycleDays=7,checklist=[dict(itemId='VISUAL',name='外观',standard='无松动',required=True),dict(itemId='VIB',name='振动',standard='小于5',required=True)]),201)
    plan=call('生成点检任务','/maintenance-plans/'+newplan['planId']+'/inspection-tasks','POST',{},201) if newplan and newplan.get('planId') else None
    if plan and plan.get('taskNo'):
        path='/inspection-tasks/'+plan['taskNo'];call('点检任务开始',path+'/start','PATCH',dict(operator='演示点检员'))
        defect=call('点检发现缺陷','{}/submit'.format(path),'POST',dict(items=[dict(itemId='VISUAL',measuredValue='无松动',result='PASS'),dict(itemId='VIB',measuredValue='6.2',result='FAIL')],conclusion='虚拟振动偏高',operator='演示点检员',defectSeverity='L2',defectDescription='虚拟驱动振动需排查'))
        if defect and defect.get('defect'):call('缺陷转工单','/maintenance-defects/'+defect['defect']['defectNo']+'/work-order','POST',{})
    order=call('新建工单','/work-orders','POST',dict(deviceCode=asset,title='[虚拟]自动验收工单',priority='P2 中',description='虚拟工单，仅本地验收',operator='演示工程师'),201)
    if order:
        path='/work-orders/'+order['orderNo'];call('工单非法跨级关闭拒绝',path+'/status','PATCH',dict(status='CLOSED'),409)
        for st in ['APPROVED','IN_PROGRESS']:call('工单流转到'+st,path+'/status','PATCH',dict(status=st,operator='演示主管',reason='虚拟流程验收'))
        reservation=call('工单备件预留',path+'/spare-reservations','POST',dict(partCode='SAMPLE-PART-0',warehouseCode='SAMPLE-WH',quantity=1,purpose='虚拟验收',requester='演示工程师'),201)
        if reservation and reservation.get('reservation'):
            no=reservation['reservation']['reservationNo'];call('备件领用','/spare-reservations/'+no+'/issue','POST',dict(quantity=1,operator='演示仓管员',reason='虚拟领用'))
    call('备件入库','/spare-parts/SAMPLE-PART-0/receipt','POST',dict(warehouseCode='SAMPLE-WH',quantity=2,reason='虚拟验收入库',operator='演示仓管员'))
    # Use a still-open seeded case, exercising real status guards and immutable repair/retest records.
    case=call('读取虚拟维修执行档案','/alarms/SAMPLE-ALM-FX-01/case')
    if case and case.get('workOrderNo') and case.get('statusCode') != 'CLOSED':
        path='/work-orders/'+case['workOrderNo']
        call('维修执行过程记录',path+'/case/execution-records','POST',dict(action='虚拟紧固与润滑',result='虚拟维修完成',safetyConfirmation='虚拟：已隔离挂牌',partsUsed='虚拟润滑油',operator='演示维修员'))
        call('维修后进入待复测',path+'/status','PATCH',dict(status='WAITING_VERIFY',operator='演示维修员',reason='虚拟维修已完成'))
        call('维修后复测通过',path+'/case/retests','POST',dict(pointCode='SAMPLE-FX-01-VIB',beforeValue=7.2,afterValue=4.2,unit='mm/s',criterion='虚拟同工况低于5mm/s',passed=True,operator='演示复测员'))
        call('告警工单复测闭环',path+'/case/close','POST',dict(conclusion='虚拟闭环验收通过',operator='演示主管'))
    call('本地AI数据检索','/assistant/chat','POST',dict(message='有哪些设备需要关注？',deviceCode=asset),predicate=lambda d:bool(d))
    call('普通用户不能管理账号','/auth/users',expected=403,role='USER')
    for role in sessions:call('退出'+role,'/auth/logout','POST',expected=204,role=role)
finally:
    report=dict(version='1.8.1',environment='LOCAL_H2_SAMPLE_NOT_SERVER',realChainTested=False,realAiTested=False,realOpenGeminiTested=False,
        createdAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),total=len(results),passed=sum(x['passed'] for x in results),results=results)
    pathlib.Path(args.output).write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({k:report[k] for k in ['version','environment','total','passed']}))
    for r in results:
        if not r['passed']:print(json.dumps(r,ensure_ascii=True))
