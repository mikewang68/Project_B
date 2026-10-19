package com.bproject.trust.selfcheck;

import com.bproject.trust.shared.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.util.*;

public final class Reports {
  public static final String CONTRACT="generic-v1@7d06990", BUILD="selfcheck-1.0.0";
  private Reports() {}
  public static Map<String,Object> record(String mode,String scenario,boolean completed,int exit,List<?> checks) {
    Map<String,Object> value = new LinkedHashMap<>();
    value.put("formatVersion",1); value.put("mode",mode); value.put("contractVersion",CONTRACT);
    value.put("buildVersion",BUILD); value.put("scenario",scenario); value.put("completed",completed);
    value.put("exitCode",exit); value.put("checks",checks);
    value.put("expectedCheckCount",checks.size());
    value.put("limitations",List.of("本地模拟或离线检查；未验证真实组件、真实授权、业务事务和调用方内部状态。"));
    return value;
  }
  public static void write(Path path,Map<String,Object> value) throws Exception { PrivateFiles.write(path,Json.write(value)); }
  public static int render(Path input,Path out) throws Exception {
    if (Files.size(input)>4_194_304) throw new IllegalArgumentException("Report too large");
    JsonNode root = Json.MAPPER.copy().enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(Files.readAllBytes(input));
    if (!root.isObject() || !root.path("formatVersion").isIntegralNumber() || !root.path("formatVersion").canConvertToInt() || root.path("formatVersion").intValue()!=1
        || !root.path("completed").isBoolean() || !root.path("exitCode").isIntegralNumber() || !root.path("exitCode").canConvertToInt()
        || !root.path("checks").isArray() || !Set.of("offline","local-simulation").contains(root.path("mode").asText())
        || !CONTRACT.equals(root.path("contractVersion").asText()) || !BUILD.equals(root.path("buildVersion").asText()))
      throw new IllegalArgumentException("Invalid or incomplete report");
    int code=root.path("exitCode").asInt();
    if (code<0 || code>2) throw new IllegalArgumentException("Invalid report exit code");
    int failures=0;
    Set<String> observed=new HashSet<>();
    for (var check:root.path("checks")) {
      if (!check.path("passed").isBoolean() || !check.path("name").isTextual() || !check.path("reason").isTextual())
        throw new IllegalArgumentException("Invalid check record");
      if (!check.path("passed").asBoolean()) failures++;
      if (!observed.add(check.path("name").asText()) && !check.path("name").asText().equals("unexpected-interaction"))
        throw new IllegalArgumentException("Duplicate check record");
    }
    boolean completeSchema;
    if(root.path("mode").asText().equals("local-simulation")) {
      var required=Simulator.requiredChecks(root.path("scenario").asText());
      completeSchema=observed.containsAll(required) && observed.stream().allMatch(n->required.contains(n)||n.equals("unexpected-interaction"));
    } else {
      int count=root.path("expectedCheckCount").canConvertToInt()?root.path("expectedCheckCount").asInt(-1):-1;
      completeSchema=root.path("scenario").asText().equals("input-contract") && count>0 && count<=1000 && count==observed.size();
      for(int i=1;completeSchema && i<=count;i++) completeSchema=observed.contains("request-"+i);
    }
    if (!completeSchema || !root.path("completed").asBoolean() || root.path("checks").isEmpty()) code=2;
    else if (failures>0 && code==0) code=1;
    String summary="TRUST 自检 — 本地模拟/离线检查\ncontract="+root.path("contractVersion").asText()+"\nbuild="+root.path("buildVersion").asText()
        +"\nscenario="+root.path("scenario").asText()+"\nexitCode="+code+"\n";
    StringBuilder xml=new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<testsuite name=\"TRUST local selfcheck\" tests=\"")
        .append(root.path("checks").size()+1).append("\" failures=\"").append(failures+(code==0?0:1)).append("\">\n");
    for(var c:root.path("checks")) {
      summary+=(c.path("passed").asBoolean()?"PASS ":"FAIL ")+c.path("name").asText()+": "+c.path("reason").asText()+"\n";
      xml.append("<testcase name=\"").append(escape(c.path("name").asText())).append("\">");
      if(!c.path("passed").asBoolean()) xml.append("<failure message=\"").append(escape(c.path("reason").asText())).append("\"/>");
      xml.append("</testcase>\n");
    }
    xml.append("<testcase name=\"run-complete\">");
    if(code!=0) xml.append("<failure message=\"Run failed or incomplete; exit code ").append(code).append("\"/>");
    xml.append("</testcase>\n</testsuite>\n");
    // Only copy the defined report schema; never echo arbitrary extra fields into output.
    List<Map<String,Object>> safe = new ArrayList<>();
    for(var c:root.path("checks")) safe.add(Map.of("name",c.path("name").asText(),"passed",c.path("passed").asBoolean(),"reason",c.path("reason").asText()));
    var clean=record(root.path("mode").asText(),root.path("scenario").asText(),root.path("completed").asBoolean(),code,safe);
    clean.put("contractVersion",root.path("contractVersion").asText()); clean.put("buildVersion",root.path("buildVersion").asText());
    Path json=Path.of(out+".json"),txt=Path.of(out+".txt"),junit=Path.of(out+".xml");
    for(var path:List.of(json,txt,junit)) { PrivateFiles.output(path); if(Files.exists(path)) throw new IllegalArgumentException("Choose a fresh summary path"); }
    write(json,clean); PrivateFiles.write(txt,summary); PrivateFiles.write(junit,xml.toString());
    System.out.print(summary); return code;
  }
  private static String escape(String text) { return text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;"); }
}
