package com.bproject.safety.config;

/** The same operation codes are used by the browser and central permission catalog. */
public final class ScsRoutePermissions {
  private ScsRoutePermissions(){}
  public static String required(String method,String path){
    if(!path.startsWith("/api/v1/"))return null;
    String p=path.substring(8);boolean read=method.equals("GET")||method.equals("HEAD");
    String module;
    if(p.startsWith("ai/models"))module="safety:module:ai:model";
    else if(p.startsWith("ai-events")||p.startsWith("cameras"))module="safety:module:ai";
    else if(p.startsWith("alerts"))module="safety:module:alert";
    else if(p.startsWith("rules"))module="safety:module:rule";
    else if(p.startsWith("ops")||p.startsWith("edge"))module="safety:module:ops";
    else if(p.startsWith("collision"))module="safety:module:collision";
    else if(p.startsWith("analytics"))module="safety:module:stat";
    else if(p.startsWith("fences"))module="safety:fence";
    else if(p.startsWith("personnel")||p.startsWith("persons"))module="safety:person";
    else if(p.startsWith("screen"))module="safety:screen";
    else if(p.startsWith("mobile"))module="safety:mobile";
    else if(read&&(p.startsWith("overview")||p.startsWith("meta/")||p.equals("state")))return "safety:home:map:view";
    // 写操作此前落到绑定文件 routes，无条目即 403：非 ADMIN 永远无法使用首页的演示开关
    else if(!read&&p.startsWith("overview"))return "safety:home:map:edit";
    else return null;
    if(p.matches(".*/(export|download)(/.*)?"))return module+":export";
    if(read)return module+switch(module){case "safety:fence"->":list:view";case "safety:person"->":map:view";default->":view";};
    if(module.equals("safety:module:alert"))return module+(p.matches(".*/(review|review-reject)")?":approve":":handle");
    if(module.equals("safety:module:ai:model"))return module+(p.matches(".*/(infer|escalate)")?":execute":":edit");
    if(module.equals("safety:module:ai"))return module+(p.endsWith("/assign")?":assign":":edit");
    if(module.equals("safety:module:rule"))return module+(p.matches(".*/(approve|reject)")?":approve":p.matches(".*/(publish|redeliver|rollback|simulate|simulate-mismatch|conflict-check)")?":execute":":edit");
    if(module.equals("safety:person"))return "safety:person:config:edit";
    if(module.equals("safety:fence"))return module+(p.matches(".*/(publish|redeliver)")?":publish":":edit");
    return module+":execute";
  }
}
