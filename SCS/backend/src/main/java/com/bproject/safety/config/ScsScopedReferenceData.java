package com.bproject.safety.config;

import java.util.*;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** Filters cached device identities through their current registered database ownership. */
@Component
public class ScsScopedReferenceData {
  private final NamedParameterJdbcTemplate jdbc;
  private final NamedParameterJdbcTemplate raw;
  public ScsScopedReferenceData(@Autowired(required=false) NamedParameterJdbcTemplate jdbc){
    this.raw=jdbc;this.jdbc=jdbc==null?null:new ScsScopedJdbcTemplate(jdbc);
  }
  /** Unregistered legacy streams may not modify cached identities assigned to any scope. */
  public boolean legacyIngestAllowed(ScsCentralScope.Root root,String key){
    if(!ScsCentralScope.legacyMachineIngress())return true;
    if(raw==null||key==null)return false;
    Long count=raw.queryForObject("SELECT count(*) FROM safety."+root.table+" WHERE "+root.key+"=:key AND (project_id IS NOT NULL OR region_id IS NOT NULL OR subsystem_code IS NOT NULL)",Map.of("key",key),Long.class);
    return count!=null&&count==0;
  }
  /** Lock the pre-registered cache identity; a machine never adopts an unassigned object. */
  public boolean scopedMachineIngestAllowed(ScsCentralScope.Root root,String key){
    var machine=ScsMachineRegistry.current();if(machine==null)return true;
    if(raw==null||key==null||!machine.owns(root,key))return false;
    if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Machine cache update requires a transaction");
    var rows=raw.queryForList("SELECT project_id,region_id,subsystem_code FROM safety."+root.table+" WHERE "+root.key+"=:key FOR UPDATE",Map.of("key",key));
    var s=machine.scope();return rows.size()==1&&s.projectId().equals(rows.get(0).get("project_id"))&&s.regionId().equals(rows.get(0).get("region_id"))&&s.subsystemCode().equals(rows.get(0).get("subsystem_code"));
  }
  /** A protected source may populate a cache for an existing, owned area; never create its root. */
  public Optional<String> machineAreaName(String key){
    if(ScsMachineRegistry.current()==null||jdbc==null)return Optional.empty();
    var names=jdbc.queryForList("SELECT area_name FROM safety.safety_area WHERE area_code=:key",Map.of("key",key),String.class);
    return names.size()==1?Optional.ofNullable(names.get(0)):Optional.empty();
  }
  public <T> List<T> visible(ScsCentralScope.Root root,Collection<T> input,Function<T,String> key){
    if(ScsCentralScope.current()==null)return List.copyOf(input);
    if(jdbc==null)return List.of();
    Set<String> allowed=new HashSet<>(jdbc.queryForList("SELECT "+root.key+" FROM safety."+root.table,Map.of(),String.class));
    return input.stream().filter(v->allowed.contains(key.apply(v))).toList();
  }
  public boolean snapshotVisible(String filename){
    if(ScsCentralScope.current()==null)return true;
    if(jdbc==null||filename==null||!filename.matches("[A-Za-z0-9_-]+\\.(jpg|jpeg|png)"))return false;
    Long count=jdbc.queryForObject("SELECT count(*) FROM safety.ai_event WHERE (detection_boxes->>'snapshotUrl'=:filename OR right(detection_boxes->>'snapshotUrl',length(:filename)+1)='/'||:filename)",Map.of("filename",filename),Long.class);
    return count!=null&&count>0;
  }
}
