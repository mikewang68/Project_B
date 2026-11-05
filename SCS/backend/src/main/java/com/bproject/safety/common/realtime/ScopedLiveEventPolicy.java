package com.bproject.safety.common.realtime;

import com.bproject.iam.context.*;
import com.bproject.safety.config.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** Server-side event classification. Client event data never supplies an authorization tuple. */
@Component
public class ScopedLiveEventPolicy {
  public record View(String menu,String nativePermission) {}
  public record Audience(Set<ScopeTriple> scopes,boolean globalReference,boolean resolved,boolean legacy) {
    public Audience { scopes=Set.copyOf(scopes); }
    public Audience(Set<ScopeTriple> scopes,boolean globalReference,boolean resolved){this(scopes,globalReference,resolved,false);}
    static Audience denied(){return new Audience(Set.of(),false,false,false);}
    static Audience verifiedLegacy(){return new Audience(Set.of(),false,true,true);}
  }
  private final NamedParameterJdbcTemplate jdbc;
  public ScopedLiveEventPolicy(@Autowired(required=false) NamedParameterJdbcTemplate jdbc){this.jdbc=jdbc;}

  public static List<View> views(String type){
    if(type.equals("person.config.changed"))return List.of(new View("SCS:people","safety:person:map:view"));
    if(type.startsWith("ai.model"))return List.of(new View("SCS:ai-models","safety:module:ai:model:view"));
    if(type.startsWith("alert."))return List.of(new View("SCS:alarms","safety:module:alert:view"),new View("SCS:mobile-alerts","safety:mobile:view"),new View("SCS:mobile-home","safety:mobile:view"),new View("SCS:overview","safety:home:map:view"),new View("SCS:safety-screen","safety:screen:view"),new View("SCS:analytics","safety:module:stat:view"));
    if(type.startsWith("ai."))return List.of(new View("SCS:ai","safety:module:ai:view"));
    if(type.startsWith("person."))return List.of(new View("SCS:people","safety:person:map:view"),new View("SCS:overview","safety:home:map:view"),new View("SCS:safety-screen","safety:screen:view"));
    if(type.startsWith("fence."))return List.of(new View("SCS:fences","safety:fence:list:view"));
    if(type.startsWith("collision."))return List.of(new View("SCS:devices","safety:module:collision:view"));
    if(type.startsWith("rule."))return List.of(new View("SCS:rules","safety:module:rule:view"));
    if(type.startsWith("ops."))return List.of(new View("SCS:operations","safety:module:ops:view"));
    if(type.startsWith("zone."))return List.of(new View("SCS:overview","safety:home:map:view"),new View("SCS:people","safety:person:map:view"),new View("SCS:safety-screen","safety:screen:view"));
    return List.of();
  }
  public static boolean mayConnect(UserContext c){
    if(c==null)return true;
    return List.of("alert.changed","ai.changed","ai.model_selected","person.moved","fence.changed","collision.changed","rule.changed","ops.node.changed","zone.changed").stream()
      .flatMap(t->views(t).stream()).anyMatch(v->c.hasAction(v.menu(),"VIEW"));
  }
  public static boolean mayConnect(ScsLocalPolicy.Actor actor){
    if(actor.context()==null)return true;
    return List.of("alert.changed","ai.changed","ai.model_selected","person.moved","fence.changed","collision.changed","rule.changed","ops.node.changed","zone.changed").stream().flatMap(t->views(t).stream())
      .anyMatch(v->actor.context().hasAction(v.menu(),"VIEW")&&(actor.admin()||actor.permissions().contains(v.nativePermission())));
  }
  public static boolean permits(ScsLocalPolicy.Actor actor,String type,Audience event){
    var c=actor.context();if(c==null||!event.resolved())return false;
    if(views(type).stream().noneMatch(v->c.hasAction(v.menu(),"VIEW")&&(actor.admin()||actor.permissions().contains(v.nativePermission()))))return false;
    if(event.globalReference())return true; // Explicit global reference changes contain no business payload.
    if(event.scopes().isEmpty())return false;
    return c.global()||c.scopeMode()==ScopeSql.Mode.SCOPED&&c.activeScope().containsAll(event.scopes());
  }

  public Audience resolve(String type,JsonNode data){
    if(views(type).isEmpty())return Audience.denied();
    // These global-reference configuration events only invalidate an explicitly authorized view.
    if(Set.of("person.config.changed","ai.model_selected").contains(type))return new Audience(Set.of(),true,true);
    String table,key;List<String> fields;
    if(type.startsWith("alert.")){table="safety_alert";key="alert_no";fields=List.of("alertId");}
    else if(type.startsWith("ai.")){table="ai_event";key="event_no";fields=List.of("aiEventId");}
    else if(type.startsWith("person.")){table="safety_personnel";key="personnel_code";fields=List.of("personnelId","personnelIds","personId");}
    else if(type.startsWith("fence.")){table="safety_fence";key="fence_code";fields=List.of("fenceId");}
    else if(type.startsWith("collision.")){table="collision_device";key="device_code";fields=List.of("deviceId","relatedDeviceId","deviceIds");}
    else if(type.startsWith("rule.")){table="safety_rule";key="rule_code";fields=List.of("ruleId");}
    else if(type.startsWith("ops.")){table="edge_node";key="node_code";fields=List.of("nodeId");}
    else if(type.startsWith("zone.")){table="safety_area";key="area_code";fields=List.of("code");}
    else return Audience.denied();
    Set<String> ids=new LinkedHashSet<>();
    for(String field:fields){var value=data.path(field);if(value.isArray())value.forEach(v->{if(v.isTextual())ids.add(v.asText());});else if(value.isTextual())ids.add(value.asText());}
    if(ids.size()>1000||ids.stream().anyMatch(s->s.isBlank()||s.length()>128))return Audience.denied();
    if(ids.isEmpty()){
      // Some existing aggregate notifications omit identifiers. Only a trusted, single write
      // context can supply their audience. Legacy/background/no-context events remain excluded.
      var c=ScsCentralScope.current();
      return c!=null&&!c.readOnly()&&c.scopeMode()==ScopeSql.Mode.SCOPED&&c.activeScope().size()==1
        ?new Audience(Set.copyOf(c.activeScope()),false,true):Audience.denied();
    }
    if(jdbc==null)return Audience.denied();
    try{
      var rows=jdbc.queryForList("SELECT "+key+",project_id,region_id,subsystem_code FROM safety."+table+" WHERE "+key+" IN (:ids)",Map.of("ids",ids));
      if(rows.size()!=ids.size())return Audience.denied();
      Set<ScopeTriple> scopes=new HashSet<>();int legacy=0;
      for(var row:rows){
        Object p=row.get("project_id"),r=row.get("region_id"),s=row.get("subsystem_code");
        if(p==null&&r==null&&s==null||"LEGACY".equals(p)&&"UNASSIGNED".equals(r)&&"SCS".equals(s)){legacy++;continue;}
        if(!(p instanceof String project)||!(r instanceof String region)||!(s instanceof String system)
            ||!project.matches("[A-Za-z0-9_-]+")||!region.matches("[A-Za-z0-9_-]+")||!system.equals("SCS")
            ||project.equals("LEGACY")||region.equals("UNASSIGNED"))return Audience.denied();
        scopes.add(new ScopeTriple(project,region,system));
      }
      if(legacy>0)return legacy==rows.size()?Audience.verifiedLegacy():Audience.denied();
      return new Audience(scopes,false,true);
    }catch(Exception unavailable){return Audience.denied();}
  }
}
