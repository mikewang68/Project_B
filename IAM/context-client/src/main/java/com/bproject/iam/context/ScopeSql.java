package com.bproject.iam.context;

import java.util.*;

/** Only trusted server code may choose mode, tuples and a registered column profile. */
public final class ScopeSql {
  private ScopeSql() {}
  public enum Mode { GLOBAL, SCOPED, DENY }
  public enum Columns {
    STANDARD("project_id","region_id","subsystem_code"),
    ORDER("o.project_id","o.region_id","o.subsystem_code");
    final String project,region,system;
    public String projectColumn(){return project;}
    Columns(String p,String r,String s){project=p;region=r;system=s;}
  }
  public record Filters(String my_info,String my_project_zone_info,
      String my_info_template,String my_project_zone_info_template,Map<String,String> scope_params) {}
  public static Filters build(Mode mode,Collection<ScopeTriple> scopes) {
    return build(mode,scopes,Columns.STANDARD);
  }
  public static Filters build(Mode mode,Collection<ScopeTriple> scopes,Columns columns) {
    Objects.requireNonNull(mode);Objects.requireNonNull(columns);
    if(mode==Mode.GLOBAL)return new Filters(" AND 1=1 "," AND 1=1 "," AND 1=1 "," AND 1=1 ",Map.of());
    var tuples=new TreeSet<>(Objects.requireNonNull(scopes));
    if(mode==Mode.DENY||tuples.isEmpty())return new Filters(" AND 1=0 "," AND 1=0 "," AND 1=0 "," AND 1=0 ",Map.of());
    if(tuples.size()>512)throw new IllegalArgumentException("TOO_MANY_SCOPE_TUPLES");
    var raw=new ArrayList<String>();var shared=new ArrayList<String>();
    var template=new ArrayList<String>();var sharedTemplate=new ArrayList<String>();
    var parameters=new LinkedHashMap<String,String>();var pairs=new HashSet<List<String>>();
    int i=0;
    for(var tuple:tuples) {
      String p=ScopeTriple.identifier(tuple.projectId(),64),r=ScopeTriple.identifier(tuple.regionId(),64),
          s=ScopeTriple.identifier(tuple.subsystemCode(),32);
      raw.add(columns.project+" = '"+p+"' AND "+columns.region+" = '"+r+"' AND "+columns.system+" = '"+s+"'");
      template.add(columns.project+" = :p"+i+" AND "+columns.region+" = :r"+i+" AND "+columns.system+" = :s"+i);
      parameters.put("p"+i,p);parameters.put("r"+i,r);parameters.put("s"+i,s);
      if(pairs.add(List.of(p,r))) {
        shared.add(columns.project+" = '"+p+"' AND "+columns.region+" = '"+r+"'");
        sharedTemplate.add(columns.project+" = :p"+i+" AND "+columns.region+" = :r"+i);
      }
      i++;
    }
    return new Filters(join(raw),join(shared),join(template),join(sharedTemplate),Collections.unmodifiableMap(parameters));
  }
  private static String join(List<String> terms) {
    return terms.size()==1?" AND "+terms.get(0)+" ":" AND (("+String.join(") OR (",terms)+")) ";
  }
}
