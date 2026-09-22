package com.bdemo.energy.agent;

import com.bdemo.common.BusinessException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;import java.sql.Statement;import java.sql.Timestamp;import java.time.LocalDateTime;import java.util.LinkedHashMap;import java.util.List;import java.util.Map;
@Service public class AgentService{
 private final CloudLlmClient llm;private final JdbcTemplate jdbc;private final ObjectMapper json;private final LocalDateTime now;public AgentService(JdbcTemplate j,ObjectMapper o,@Value("${b-demo.demo-now}")LocalDateTime n,CloudLlmClient llm){jdbc=j;json=o;now=n;this.llm=llm;}
 public String answer(String message){Map<String,Object>s=jdbc.queryForMap("SELECT COUNT(*) alerts,(SELECT COUNT(*) FROM e_suggestion WHERE status NOT LIKE '%closed') suggestions FROM e_alert_event WHERE status NOT IN ('closed','false_closed')");if(llm.enabled())return llm.answer(message,s);return "本地汇总（云端模型未启用）：未关闭告警 "+s.get("alerts")+" 条，待闭环建议 "+s.get("suggestions")+" 条。你问的是："+message;}
 public Map<String,Object>list(int page,int size){Integer total=jdbc.queryForObject("SELECT COUNT(*) FROM ai_inspection_report",Integer.class);List<Map<String,Object>>items=jdbc.queryForList("SELECT * FROM ai_inspection_report ORDER BY report_date DESC,id DESC LIMIT ? OFFSET ?",size,(page-1)*size).stream().map(this::item).toList();return map("items",items,"total",total,"pageNum",page,"pageSize",size);}
 public Map<String,Object>detail(long id){List<Map<String,Object>>r=jdbc.queryForList("SELECT * FROM ai_inspection_report WHERE id=?",id);if(r.isEmpty())throw new BusinessException(404,"巡检报告不存在");return item(r.get(0));}
 public long run(String trigger){long started=System.currentTimeMillis();Map<String,Object>s=jdbc.queryForMap("SELECT COUNT(*) openAlerts,(SELECT COUNT(*) FROM e_suggestion WHERE status NOT LIKE '%closed') openSuggestions FROM e_alert_event WHERE status NOT IN ('closed','false_closed')");String summary=llm.enabled()?llm.answer("请概括当前告警和建议汇总，并说明检查范围的限制。",s):"巡检完成：发现未闭环告警 "+s.get("openAlerts")+" 条，待办建议 "+s.get("openSuggestions")+" 条。";KeyHolder k=new GeneratedKeyHolder();jdbc.update(c->{PreparedStatement p=c.prepareStatement("INSERT INTO ai_inspection_report(report_date,trigger_type,status,summary,findings_json,stats_json,model_name,elapsed_ms,created_at) VALUES(?,?,'completed',?,?,?,?,?,?)",new String[]{"id"});p.setString(1,now.toLocalDate().toString());p.setString(2,trigger);p.setString(3,summary);p.setString(4,"[]");p.setString(5,toJson(s));p.setString(6,llm.enabled()?llm.model():"deterministic-spring");p.setInt(7,(int)(System.currentTimeMillis()-started));p.setTimestamp(8,Timestamp.valueOf(now));return p;},k);return k.getKey().longValue();}
 private Map<String,Object>item(Map<String,Object>r){return map("reportId",r.get("id"),"reportDate",r.get("report_date"),"triggerType",r.get("trigger_type"),"status",r.get("status"),"summary",r.get("summary"),"findings",parse(r.get("findings_json")),"stats",parse(r.get("stats_json")),"modelName",r.get("model_name"),"elapsedMs",r.get("elapsed_ms"),"createdAt",r.get("created_at"));}private Object parse(Object v){try{return json.readValue(String.valueOf(v),new TypeReference<Object>(){});}catch(Exception e){return Map.of();}}private String toJson(Object v){try{return json.writeValueAsString(v);}catch(Exception e){return"{}";}}private Map<String,Object>map(Object...p){Map<String,Object>m=new LinkedHashMap<>();for(int i=0;i<p.length;i+=2)m.put((String)p[i],p[i+1]);return m;}
}
