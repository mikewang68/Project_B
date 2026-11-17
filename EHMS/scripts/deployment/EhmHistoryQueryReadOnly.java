import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.*;
import java.util.*;

/** Real-engine bounded-history syntax/timing checks; never outputs payloads or credentials. */
public class EhmHistoryQueryReadOnly {
 static String env(String n){return Objects.requireNonNullElse(System.getenv(n),"");}
 static String id(String v){if(!v.matches("[A-Za-z_][A-Za-z0-9_]{0,62}"))throw new IllegalArgumentException("INVALID_IDENTIFIER");return v;}
 public static void main(String[] args)throws Exception{
  var props=new Properties();props.setProperty("user",env("SPRING_DATASOURCE_USERNAME"));props.setProperty("password",env("SPRING_DATASOURCE_PASSWORD"));
  props.setProperty("connectTimeout","8");props.setProperty("socketTimeout","25");props.setProperty("ApplicationName","EHMS_READ_ONLY_HISTORY_CHECK");
  String table=id(env("EHM_PERSISTENCE_SCHEMA"))+"."+id(env("EHM_PERSISTENCE_TABLE"));
  var checks=new ArrayList<Map<String,Object>>();
  try(var db=DriverManager.getConnection(env("SPRING_DATASOURCE_URL"),props)){
   for(String region:List.of("EHMS_DEMO","SHEN_TIE","UNASSIGNED"))for(String type:List.of("health-assessment","sample-telemetry","audit")){
    db.setReadOnly(true);db.setAutoCommit(false);try(var s=db.createStatement()){s.execute("SET TRANSACTION READ ONLY");}
    try(var s=db.createStatement();var r=s.executeQuery("SHOW transaction_read_only")){if(!r.next()||!"on".equalsIgnoreCase(r.getString(1)))throw new IllegalStateException("READ_ONLY_NOT_VERIFIED");}
    Map<String,Object> out=new LinkedHashMap<>();out.put("region",region);out.put("type",type);
    String field=type.equals("health-assessment")?"assetCode":type.equals("sample-telemetry")?"pointCode":null;
    String timeField=type.equals("health-assessment")?"generatedAt":type.equals("sample-telemetry")?"sourceTimestamp":"occurredAt";
    String predicate=" FROM "+table+" o WHERE project_id=? AND region_id=? AND subsystem_code='EHMS' AND aggregate_type=?";
    long started=System.nanoTime();
    try{
     String value=null;
     if(field!=null)try(var q=db.prepareStatement("SELECT json_extract_path_text(CAST(payload AS json),'"+field+"')"+predicate+" LIMIT 1")){
      q.setQueryTimeout(20);q.setString(1,"PROJECT_B");q.setString(2,region);q.setString(3,type);try(var r=q.executeQuery()){if(r.next())value=r.getString(1);}
     }
     if(field!=null&&value==null){out.put("status","NO_RECORDS");}
     else {
      String raw="json_extract_path_text(CAST(o.payload AS json),'"+timeField+"')";
      String time="(CASE WHEN "+raw+" ~ '^-?[0-9]+([.][0-9]+)?([eE][+-]?[0-9]+)?$' THEN to_timestamp(CAST("+raw+" AS DOUBLE PRECISION)) ELSE CAST(NULLIF("+raw+",'') AS TIMESTAMP WITH TIME ZONE) END)";
      String filter=field==null?"":" AND json_extract_path_text(CAST(o.payload AS json),'"+field+"')=?";
      try(var q=db.prepareStatement("SELECT "+time+predicate+filter+" ORDER BY "+time+" DESC NULLS LAST,aggregate_id DESC LIMIT ?")){
       q.setQueryTimeout(20);q.setString(1,"PROJECT_B");q.setString(2,region);q.setString(3,type);int i=4;if(field!=null)q.setString(i++,value);q.setInt(i,12);
       int rows=0;try(var r=q.executeQuery()){while(r.next()){r.getTimestamp(1);rows++;}}out.put("status","VERIFIED");out.put("returnedRows",rows);
      }
     }
    }catch(SQLException e){out.put("status","QUERY_FAILED");out.put("sqlState",Objects.toString(e.getSQLState(),"UNKNOWN"));}
    finally{out.put("elapsedMs",(System.nanoTime()-started)/1_000_000);db.rollback();checks.add(out);}
   }
  }
  System.out.println(new ObjectMapper().writeValueAsString(Map.of("schema","EHMS_HISTORY_READONLY_CHECK_V1","databaseReadOnlyVerified",true,
   "checks",checks,"payloadsOrCredentialsIncluded",false,"schemaOrDataModified",false)));
 }
}
