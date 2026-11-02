import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.annotation.Configuration;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Empty config context: loads Spring configuration, NEVER scans EHM or enables auto-configuration.
 * Output is captured privately by the installer; never print this result to the user's terminal.
 */
@Configuration(proxyBeanMethods=false)
public class SpringConfigInspector {
 public static void main(String[] ignored)throws Exception{
  var mapper=new ObjectMapper();
  String properties=System.getenv("EHM_INSPECTOR_SYSTEM_PROPERTIES");
  if(properties!=null){
   Map<String,String> input=mapper.readValue(new String(Base64.getDecoder().decode(properties),StandardCharsets.UTF_8),new TypeReference<Map<String,String>>(){});
   input.forEach(System::setProperty);
  }
  List<String> cli=mapper.readValue(new String(Base64.getDecoder().decode(System.getenv("EHM_INSPECTOR_ARGUMENTS")),StandardCharsets.UTF_8),new TypeReference<List<String>>(){});
  var application=new SpringApplication(SpringConfigInspector.class);
  application.setWebApplicationType(WebApplicationType.NONE);application.setBannerMode(Banner.Mode.OFF);
  application.setLogStartupInfo(false);application.setRegisterShutdownHook(false);
  try(var context=application.run(cli.toArray(String[]::new))){
   var environment=context.getEnvironment();var result=new LinkedHashMap<String,Object>();
   for(String key:List.of("ehm.auth.mode","ehm.auth.bootstrap-file","ehm.auth.table-prefix","ehm.sso.bindings-file",
       "ehm.project.default-id","ehm.persistence.schema","ehm.persistence.table","ehm.persistence.adapter",
       "ehm.telemetry.adapter","spring.datasource.url","spring.datasource.username","spring.datasource.password",
       "spring.profiles.active","ehm.config.path","spring.config.location","spring.config.additional-location","spring.config.import"))result.put(key,environment.getProperty(key));
   result.put("inspector.context","EMPTY_NO_AUTOCONFIG_NO_COMPONENT_SCAN");
   System.out.println("PRIVATE_INSPECTOR_RESULT="+mapper.writeValueAsString(result));
  }
 }
}
