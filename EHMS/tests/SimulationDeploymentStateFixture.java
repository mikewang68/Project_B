import java.nio.file.*;
import java.sql.*;
import java.util.jar.JarFile;

/** Synthetic-only standalone H2 integration fixture, never run against a server database. */
public class SimulationDeploymentStateFixture {
 static Connection db;
 static String project="PROJECT_TEST",asset,point;
 static void put(String type,String id,String payload)throws Exception{
  try(var q=db.prepareStatement("MERGE INTO ehm.aggregate_store KEY(project_id,aggregate_type,aggregate_id) VALUES(?,?,?,?)")){
   q.setString(1,project);q.setString(2,type);q.setString(3,id);q.setString(4,payload);q.executeUpdate();
  }
 }
 static void expectRejected(Path snapshot,String marker)throws Exception{
  try{SimulationDeploymentState.main(new String[]{"verify",snapshot.toString()});throw new AssertionError("Unexpected verification success");}
  catch(IllegalStateException e){if(!e.getMessage().equals(marker))throw e;}
 }
 public static void main(String[] args)throws Exception{
  String url=System.getenv("SPRING_DATASOURCE_URL");
  if(!url.startsWith("jdbc:h2:mem:deploy-fixture"))throw new IllegalStateException("ISOLATED_H2_FIXTURE_ONLY");
  try(var connection=DriverManager.getConnection(url,"sa","")){
   db=connection;
   try(var s=db.createStatement()){
    s.execute("CREATE SCHEMA ehm");
    s.execute("CREATE TABLE ehm.aggregate_store(project_id varchar,aggregate_type varchar,aggregate_id varchar,payload varchar,PRIMARY KEY(project_id,aggregate_type,aggregate_id))");
    s.execute("CREATE TABLE ehm.auth_user(user_id varchar,username varchar,password_hash varchar)");
    s.execute("INSERT INTO ehm.auth_user VALUES('fixture-id','fixture-user','synthetic-only')");
   }
   try(var jar=new JarFile(System.getenv("EHM_SIMULATION_ARTIFACT"));var in=jar.getInputStream(jar.getJarEntry("BOOT-INF/classes/simulation/equipment-scope.json"))){
    for(var entry:SimulationDeploymentState.JSON.readTree(in).path("entries")){
     String code="SAMPLE-LIVE-"+entry.path("scopeCode").asText();if(asset==null)asset=code;
     put("simulation-progress",code,"{\"assetCode\":\""+code+"\"}");
    }
   }
   point=asset+"-TEMP";
   put("simulation-control","control","{\"initialized\":true,\"running\":true,\"ticks\":1}");
   put("simulation-round","latest","{\"samplesWritten\":87}");
   put("asset",asset,"{\"code\":\""+asset+"\",\"name\":\"[virtual]fixture\"}");
   put("measurement-point",point,"{\"code\":\""+point+"\",\"assetCode\":\""+asset+"\",\"sourceProtocol\":\"SYNTHETIC\",\"sourceAddress\":\"sample://"+point+"\"}");
   String snapshot="{\"deviceCode\":\""+asset+"\",\"ready\":\"虚拟运行\",\"health\":80}";
   String quality="{\"pointCode\":\""+point+"\",\"assetCode\":\""+asset+"\",\"value\":20}";
   put("device-snapshot",asset,snapshot);put("point-quality",point,quality);
   put("device-snapshot","REAL-1","{\"deviceCode\":\"REAL-1\",\"health\":80}");
   // A SAMPLE-LIVE prefix alone, without owned progress/catalog identity, is never exempt.
   put("device-snapshot","SAMPLE-LIVE-FOREIGN","{\"deviceCode\":\"SAMPLE-LIVE-FOREIGN\",\"ready\":\"虚拟运行\",\"health\":80}");
   Path file=Path.of(args[0]);SimulationDeploymentState.main(new String[]{"snapshot",file.toString()});
   put("device-snapshot",asset,snapshot.replace(":80",":79"));put("point-quality",point,quality.replace(":20",":21"));
   SimulationDeploymentState.main(new String[]{"verify",file.toString()});
   put("device-snapshot","REAL-1","{\"deviceCode\":\"REAL-1\",\"health\":79}");expectRejected(file,"EXISTING_BUSINESS_RECORD_CHANGED");
   put("device-snapshot","REAL-1","{\"deviceCode\":\"REAL-1\",\"health\":80}");
   put("device-snapshot","SAMPLE-LIVE-FOREIGN","{\"deviceCode\":\"SAMPLE-LIVE-FOREIGN\",\"ready\":\"虚拟运行\",\"health\":79}");expectRejected(file,"EXISTING_BUSINESS_RECORD_CHANGED");
   put("device-snapshot","SAMPLE-LIVE-FOREIGN","{\"deviceCode\":\"SAMPLE-LIVE-FOREIGN\",\"ready\":\"虚拟运行\",\"health\":80}");
   put("asset",asset,"{}");expectRejected(file,"EXISTING_BUSINESS_RECORD_CHANGED");
   put("asset",asset,"{\"code\":\""+asset+"\",\"name\":\"[virtual]fixture\"}");
   try(var s=db.createStatement()){s.execute("UPDATE ehm.auth_user SET password_hash='changed-fixture'");}
   expectRejected(file,"ACCOUNT_ROWS_CHANGED");
   System.out.println("JDBC_RETRY_SAFETY_FIXTURE_PASSED: virtual snapshots advance; real/static/foreign/account changes rejected");
  }
 }
}
