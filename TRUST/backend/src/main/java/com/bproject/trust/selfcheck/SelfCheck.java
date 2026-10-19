package com.bproject.trust.selfcheck;

import com.bproject.trust.ingress.*;
import java.nio.file.*;
import java.util.*;

/** Standalone CLI. Does not bootstrap Spring, inspect environment or call external processes. */
public final class SelfCheck {
  private SelfCheck() {}
  public static void main(String[] args) { System.exit(run(args)); }
  public static int run(String... args) {
    try {
      if (args.length==0 || args[0].equals("--help")) { usage(); return args.length==0?2:0; }
      Map<String,String> options=new HashMap<>(); List<String> files=new ArrayList<>();
      for(int i=1;i<args.length;i++) {
        if(args[i].startsWith("--")) {
          if(i+1==args.length || args[i+1].startsWith("--") || options.put(args[i],args[++i])!=null)
            throw new IllegalArgumentException("Missing or repeated option");
        } else files.add(args[i]);
      }
      switch(args[0]) {
        case "check": {
          allowed(options,Set.of("--config","--report"));
          Path report=fresh(required(options,"--report"));
          Reports.write(report,Reports.record("offline","input-contract",false,2,List.of()));
          var source=Profile.read(required(options,"--config"));
          if(files.isEmpty() || files.size()>1000) throw new IllegalArgumentException("Provide 1 to 1000 request files");
          List<Map<String,Object>> checks=new ArrayList<>(); int exit=0;
          for(int index=0;index<files.size();index++) {
            byte[] bytes;
            try(var stream=Files.newInputStream(Path.of(files.get(index)))) { bytes=stream.readNBytes(GenericEventInput.LIMIT+1); }
            try {
              source.require("submit"); var value=GenericEventInput.parse(bytes); source.type(value.eventType());
              // Check the canonical size for a new version-one record; later version digits and
              // database-dependent replay/correction decisions remain explicitly unverified.
              GenericEventRules.canonical(source,value,"00000000-0000-0000-0000-000000000000","00000000-0000-0000-0000-000000000000",1);
              checks.add(Map.of("name","request-"+(index+1),"passed",true,"reason","Input accepted by shared parser and synthetic permissions; database scope and previous version NOT VERIFIED"));
            } catch(IngressError e) {
              exit=1;
              String diagnostic=e.code;
              for(var d:GenericEventInput.diagnose(bytes)) diagnostic+="; "+d.path()+" "+d.rule()+" — "+d.suggestion();
              if(e.status==413) diagnostic+="; $ REQUEST_TOO_LARGE — reduce JSON content; canonical metadata also counts toward the 262144-byte new-record limit";
              if(e.status==403) diagnostic+="; $.eventType / synthetic profile: verify allowed event types and submit permission";
              checks.add(Map.of("name","request-"+(index+1),"passed",false,"reason",diagnostic));
            }
          }
          Reports.write(report,Reports.record("offline","input-contract",true,exit,checks));
          for(var check:checks) System.out.println((Boolean.TRUE.equals(check.get("passed"))?"PASS ":"FAIL ")+check.get("name")+": "+check.get("reason"));
          System.out.println("Offline only: HTTP transport, database ownership, prior versions and real authorization NOT VERIFIED.");
          return exit;
        }
        case "serve": {
          allowed(options,Set.of("--config","--scenario","--seconds","--connection","--report"));
          if(!files.isEmpty()) throw new IllegalArgumentException("Unexpected positional argument");
          Path report=fresh(required(options,"--report")),connection=fresh(required(options,"--connection"));
          if(report.equals(connection)) throw new IllegalArgumentException("Connection and report paths must differ");
          int seconds=Integer.parseInt(options.getOrDefault("--seconds","120"));
          if(seconds<1 || seconds>600) throw new IllegalArgumentException("Seconds must be 1..600");
          Reports.write(report,Reports.record("local-simulation",options.getOrDefault("--scenario","normal"),false,2,List.of()));
          return Simulator.run(required(options,"--config"),options.getOrDefault("--scenario","normal"),seconds,connection,report);
        }
        case "report": {
          allowed(options,Set.of("--input","--out"));
          if(!files.isEmpty()) throw new IllegalArgumentException("Unexpected positional argument");
          return Reports.render(required(options,"--input"),required(options,"--out"));
        }
        default: throw new IllegalArgumentException("Unknown command");
      }
    } catch(Exception e) {
      // Exception details may contain private input or paths. Do not echo them.
      System.err.println("Selfcheck configuration/execution error or incomplete run (exit 2). Check options, private output paths, profile and input files.");
      return 2;
    }
  }
  private static Path required(Map<String,String> options,String key) { if(!options.containsKey(key)) throw new IllegalArgumentException("Missing option"); return Path.of(options.get(key)); }
  private static Path fresh(Path path) throws Exception { path=PrivateFiles.output(path); if(Files.exists(path)) throw new IllegalArgumentException("Use a fresh output path"); return path; }
  private static void allowed(Map<String,String> options,Set<String> names) { if(!names.containsAll(options.keySet())) throw new IllegalArgumentException("Unknown option"); }
  private static void usage() {
    System.out.println("TRUST selfcheck (Java 17), local simulation / offline only\n"
      +"check --config source.json --report .local/check.json request.json [request2.json ...]\n"
      +"serve --config source.json --scenario normal --seconds 120 --connection .local/connection.json --report .local/run.json\n"
      +"report --input .local/run.json --out .local/summary\n"
      +"Exit: 0 selected checks passed; 1 contract/behavior failure; 2 execution/configuration/incomplete. Use fresh output paths.");
  }
}
