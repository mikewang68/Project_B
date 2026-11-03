package com.bdemo.energy.collection;

import com.bdemo.common.BusinessException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CollectionService {
 private final JdbcTemplate jdbc;
 private final ObjectMapper json;
 private final boolean enabled,allowLive;
 private final String token;
 private final Map<String,JsonNode> points=new LinkedHashMap<>();
 private final JsonNode catalog;
 public CollectionService(JdbcTemplate jdbc,ObjectMapper mapper,
     @Value("${emcs.collection.enabled:false}")boolean enabled,
     @Value("${emcs.collection.allow-live:false}")boolean allowLive,
     @Value("${emcs.collection.ingest-token:}")String token)throws java.io.IOException {
  this.jdbc=jdbc;this.enabled=enabled;this.allowLive=allowLive;this.token=token;
  json=mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  try(var input=new ClassPathResource("collection/point-catalog.json").getInputStream()){catalog=json.readTree(input);}
  for(var p:catalog.path("points"))if(points.put(p.path("pointCode").asText(),p)!=null)throw new IllegalStateException("Duplicate catalog code");
 }
 public void authorize(String supplied){
  if(!enabled||token.length()<32)throw new BusinessException(503,"设备采集接收未启用");
  if(supplied==null||!MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8)))throw new BusinessException(403,"采集服务身份无效");
 }
 public boolean enabled(){return enabled;}
 public JsonNode catalog(){return catalog;}
 @Transactional
 public Map<String,Object> accept(byte[] wire){
  if(!enabled)throw new BusinessException(503,"设备采集接收未启用");
  if(wire.length==0||wire.length>262144)throw new BusinessException(413,"事件大小无效");
  JsonNode event;String text;
  try{text=StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(wire)).toString();event=json.readTree(text);}
  catch(Exception e){throw new BusinessException(422,"事件必须是合法且无重复字段的UTF-8 JSON");}
  check(event!=null&&event.isObject()&&"b.device-event.v1".equals(event.path("schema_version").asText()),"事件Schema无效");
  String device=id(event,"device_id"),run=id(event,"run_id"),boot=id(event,"boot_id");
  check(event.path("seq").isIntegralNumber()&&event.path("seq").canConvertToLong()&&event.path("seq").asLong()>0,"seq无效");
  String eventId=run+":"+boot+":"+device+":"+event.path("seq").asLong();
  check(eventId.equals(event.path("event_id").asText()),"事件ID与来源身份不一致");
  check(event.path("simulated").isBoolean()&&event.path("replayed").isBoolean(),"模拟/补传标记缺失");
  boolean simulated=event.path("simulated").asBoolean();
  if(!simulated&&!allowLive)throw new BusinessException(403,"当前只允许模拟数据");
  long observed=timestamp(event.get("observed_ts_ms"));
  Long source=event.has("source_ts_ms")&&event.get("source_ts_ms").isNull()?null:timestamp(event.get("source_ts_ms"));
  check((source==null?"gateway_observation":"source").equals(event.path("timestamp_origin").asText()),"时间来源标记不一致");
  var devices=jdbc.queryForList("SELECT equipment_code,simulated FROM emcs_collect_device WHERE device_id=? AND enabled=1 FOR UPDATE",device);
  if(devices.isEmpty())throw new BusinessException(403,"设备未登记或已停用");
  String equipment=devices.get(0).get("equipment_code").toString();
  check(((Number)devices.get(0).get("simulated")).intValue()==(simulated?1:0),"设备模拟属性不一致");
  String digest=sha(wire);
  var prior=jdbc.queryForList("SELECT wire_sha256,point_count FROM emcs_collect_event WHERE event_id=?",eventId);
  if(!prior.isEmpty()){
   if(!digest.equals(prior.get(0).get("wire_sha256")))throw new BusinessException(409,"同一事件ID内容冲突，原始事件已保留");
   return receipt(eventId,digest,true,((Number)prior.get(0).get("point_count")).intValue());
  }
  JsonNode values=event.path("payload").path("points");
  check(values.isArray()&&values.size()>0&&values.size()<=256,"点位数量无效");
  Set<String> seen=new HashSet<>();
  for(var p:values){
   String code=p.path("point_code").asText();JsonNode definition=points.get(code);
   check(definition!=null&&equipment.equals(definition.path("equipmentCode").asText())&&seen.add(code),"点位不属于设备、未登记或重复");
   check(p.path("unit").isTextual()&&p.path("unit").asText().equals(definition.path("unit").asText()),"点位单位不一致");
   check(equipment.equals(p.path("provenance").path("equipment_code").asText()),"点位来源不一致");
   check(Set.of("GOOD","UNCERTAIN","BAD","STALE").contains(p.path("quality_code").asText()),"质量码无效");
   check(p.has("value"),"缺少读数");JsonNode value=p.get("value");
   if(value.isNull())check(!"GOOD".equals(p.path("quality_code").asText()),"GOOD质量不能对应空值");
   else validateValue(definition.path("dataType").asText(),value);
  }
  jdbc.update("INSERT INTO emcs_collect_event(event_id,device_id,wire_sha256,wire_json,observed_ts_ms,source_ts_ms,received_ts_ms,simulated,point_count) VALUES(?,?,?,?,?,?,?,?,?)",
      eventId,device,digest,text,observed,source,Instant.now().toEpochMilli(),simulated?1:0,values.size());
  for(var p:values)jdbc.update("INSERT INTO emcs_collect_reading(event_id,point_code,value_json,unit,quality_code) VALUES(?,?,?,?,?)",
      eventId,p.path("point_code").asText(),p.get("value").toString(),p.path("unit").asText(),p.path("quality_code").asText());
  return receipt(eventId,digest,false,values.size());
 }
 public Map<String,Object> list(String area,String device,int page,int size){
  if(!enabled)return Map.of("enabled",false,"rows",List.of(),"total",0);
  check(page>=1&&page<=10000&&size>=1&&size<=100,"分页参数无效");
  List<Object> args=new ArrayList<>();String where=" FROM emcs_collect_event e JOIN emcs_collect_device d ON d.device_id=e.device_id WHERE 1=1";
  if(area!=null){where+=" AND d.area_code=?";args.add(area);}
  if(device!=null&&!device.isBlank()){where+=" AND e.device_id=?";args.add(device);}
  Long total=jdbc.queryForObject("SELECT COUNT(*)"+where,Long.class,args.toArray());
  args.add(size);args.add((page-1)*size);
  var events=jdbc.queryForList("SELECT e.event_id,e.device_id,d.equipment_code,d.area_code,e.observed_ts_ms,e.source_ts_ms,e.received_ts_ms,e.simulated,e.point_count,e.wire_sha256"+where+" ORDER BY e.observed_ts_ms DESC,e.event_id DESC LIMIT ? OFFSET ?",args.toArray());
  return Map.of("enabled",true,"rows",events,"total",total==null?0:total);
 }
 public Map<String,Object> detail(String area,String eventId){
  if(!enabled)throw new BusinessException(503,"采集未启用");
  String extra=area==null?"":" AND d.area_code=?";
  Object[] params=area==null?new Object[]{eventId}:new Object[]{eventId,area};
  var found=jdbc.queryForList("SELECT e.* FROM emcs_collect_event e JOIN emcs_collect_device d ON d.device_id=e.device_id WHERE e.event_id=?"+extra,params);
  if(found.isEmpty())throw new BusinessException(404,"事件不存在或不可访问");
  var row=found.get(0);row.remove("wire_json");
  row.put("points",jdbc.queryForList("SELECT point_code,value_json,unit,quality_code FROM emcs_collect_reading WHERE event_id=? ORDER BY point_code",eventId));
  return row;
 }
 private void validateValue(String type,JsonNode value){
  switch(type){
   case "Real","Float64" -> check(value.isNumber()&&Double.isFinite(value.asDouble()),"数值类型无效");
   case "Int","Int64" -> check(value.isIntegralNumber()&&value.canConvertToLong(),"整数类型无效");
   case "Bool","Boolean" -> check(value.isBoolean(),"布尔类型无效");
   case "DateTime" -> {check(value.isTextual(),"时间类型无效");try{OffsetDateTime.parse(value.asText());}catch(Exception e){throw new BusinessException(422,"时间点须携带时区");}}
   case "String","Enum" -> check(value.isTextual()&&value.asText().length()<=512,"文本类型或长度无效");
   default -> throw new BusinessException(422,"未支持的点位类型");
  }
 }
 private long timestamp(JsonNode n){check(n!=null&&n.isIntegralNumber()&&n.canConvertToLong()&&n.asLong()>0&&n.asLong()<=Instant.now().plusSeconds(300).toEpochMilli(),"时间戳无效");return n.asLong();}
 private String id(JsonNode n,String key){String value=n.path(key).asText();check(n.path(key).isTextual()&&value.matches("[A-Za-z0-9_.-]{1,64}"),key+"无效");return value;}
 private void check(boolean value,String error){if(!value)throw new BusinessException(422,error);}
 private String sha(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
 private Map<String,Object> receipt(String id,String hash,boolean duplicate,int count){return Map.of("event_id",id,"wire_sha256",hash,"duplicate",duplicate,"point_count",count,"state","STORED","scope","EMCS_LOCAL");}
}
