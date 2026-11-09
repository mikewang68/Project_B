import {Alert,Button,Card,Form,Input,InputNumber,Space,Switch,Tag,Typography} from 'antd';
import {useEffect,useState} from 'react';
import {liveApi} from './client';

const governanceApi=(path:string,init?:RequestInit)=>liveApi('/governance'+path,init);
type AiConfigView={systemCode:string;source:'DB'|'ENV';baseUrl:string;model:string;enabled:boolean;apiKeyConfigured:boolean;
 apiKeyLast4:string|null;timeoutMs:number;maxTokens:number;version:number;updatedBy:string|null;updatedAt:string|null};

/** 系统设置里的 AI 服务配置：密钥只写不回显，保存后下一次调用生效。 */
export default function AiConfigPanel({canWrite}:{canWrite:boolean}){
 const [config,setConfig]=useState<AiConfigView>(),[error,setError]=useState(''),[busy,setBusy]=useState(false),[revision,setRevision]=useState(0);
 const [form]=Form.useForm();
 useEffect(()=>{
  let current=true;setError('');
  void governanceApi('/settings/ai').then(result=>{
   if(!current)return;
   const data=result.data as AiConfigView;setConfig(data);
   form.setFieldsValue({baseUrl:data.baseUrl,model:data.model,enabled:data.enabled,
    timeoutMs:data.timeoutMs,maxTokens:data.maxTokens,apiKey:''});
  }).catch(e=>{if(current)setError(e instanceof Error?e.message:'AI 配置读取失败');});
  return()=>{current=false;};
 },[form,revision]);
 const save=async()=>{
  if(busy)return;setBusy(true);setError('');
  try{
   const values=await form.validateFields();
   const result=await governanceApi('/settings/ai',{method:'PUT',body:JSON.stringify({
    baseUrl:values.baseUrl,model:values.model,enabled:values.enabled,
    timeoutMs:Number(values.timeoutMs),maxTokens:Number(values.maxTokens),
    ...(typeof values.apiKey==='string'&&values.apiKey.trim()?{apiKey:values.apiKey}:{}),
    expectedVersion:config?.version??0,reason:'系统设置-AI 服务配置更新'})});
   setConfig(result.data as AiConfigView);
   form.setFieldValue('apiKey','');setRevision(value=>value+1);
  }catch(e){setError(e instanceof Error?e.message:'AI 配置保存失败');}finally{setBusy(false);}
 };
 return <Card title="AI 服务配置" extra={config&&<Tag color={config.source==='DB'?'green':'blue'}>
   {config.source==='DB'?'数据库配置':'环境变量兜底'}</Tag>}>
  {error&&<Alert type="error" showIcon title={error} style={{marginBottom:12}}/>}
  <Alert type="info" showIcon title="密钥只写入、不回显"
   description="保存后下一次调用生效；页面只显示密钥后 4 位，日志与审计不记录明文。留空表示保持原密钥。"
   style={{marginBottom:12}}/>
  <Form form={form} layout="vertical" disabled={!canWrite}>
   <Space wrap size="large">
    <Form.Item name="baseUrl" label="接口地址" style={{minWidth:360}}
     rules={[{required:true,message:'请输入接口地址'},{type:'url',message:'请输入 http/https 地址'}]}>
     <Input placeholder="模型服务地址，例如 .../v1"/></Form.Item>
    <Form.Item name="model" label="模型" style={{minWidth:220}} rules={[{required:true,message:'请输入模型名'}]}>
     <Input placeholder="qwen3.8-27b"/></Form.Item>
   </Space>
   <Space wrap size="large">
    <Form.Item name="apiKey" style={{minWidth:340}}
     label={`API 密钥${config?.apiKeyConfigured?`（已配置${config.apiKeyLast4?`，尾号 ${config.apiKeyLast4}`:''}）`:''}`}>
     <Input.Password placeholder="留空表示不修改" autoComplete="new-password"/></Form.Item>
    <Form.Item name="enabled" label="启用" valuePropName="checked"><Switch/></Form.Item>
    <Form.Item name="timeoutMs" label="超时（毫秒）"><InputNumber min={1000} max={120000} step={1000} style={{width:140}}/></Form.Item>
    <Form.Item name="maxTokens" label="最大 tokens"><InputNumber min={1} max={32000} style={{width:140}}/></Form.Item>
   </Space>
   <Space>
    <Button type="primary" loading={busy} disabled={!canWrite} onClick={()=>void save()}>保存 AI 配置</Button>
    {config?.updatedAt&&<Typography.Text type="secondary">更新：
     {new Date(config.updatedAt).toLocaleString('zh-CN',{timeZone:'Asia/Shanghai'})}
     {config.updatedBy?` / ${config.updatedBy}`:''}</Typography.Text>}
   </Space>
  </Form>
 </Card>;
}
