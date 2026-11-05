package com.bproject.safety.config;

import com.bproject.iam.context.*;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.*;
import org.springframework.web.server.ResponseStatusException;

/** Separate machine principals: protected server registry, no browser grants or client tuples. */
@Component
public class ScsMachineRegistry {
  private static final String ATTRIBUTE=ScsMachineRegistry.class.getName();
  public static final Set<String> ROUTES=Set.of("/api/v1/ingest/person-position","/api/v1/ingest/device-state","/api/v1/ingest/zone-state","/api/v1/ingest/uwb-station");
  private final ObjectMapper mapper;private final String file;
  public ScsMachineRegistry(ObjectMapper mapper,@Value("${app.security.machine-bindings-file:}")String file){this.mapper=mapper;this.file=file;}
  public record Binding(String id,ScopeTriple scope,Set<String> routes,Map<ScsCentralScope.Root,Set<String>> objects,String revision,ScsMachineRegistry registry){
    public Binding {routes=Set.copyOf(routes);var copy=new EnumMap<ScsCentralScope.Root,Set<String>>(ScsCentralScope.Root.class);objects.forEach((k,v)->copy.put(k,Set.copyOf(v)));objects=Collections.unmodifiableMap(copy);}
    public void revalidate(){registry.revalidate(this);}
    public boolean owns(ScsCentralScope.Root root,String key){revalidate();return objects.getOrDefault(root,Set.of()).contains(key);}
    public UserContext context(){
      revalidate();var f=ScopeSql.build(ScopeSql.Mode.SCOPED,List.of(scope));String subject="machine-"+id;
      return new UserContext(2,subject,subject,subject,subject,"L4_OPERATOR",false,"SCS",scope.projectId(),scope.regionId(),scope.subsystemCode(),ScopeSql.Mode.SCOPED,List.of(scope),List.of(scope),Set.of("MACHINE_INGEST"),Set.of(),Map.of(),Set.of(),revision,f.my_info(),f.my_project_zone_info(),f.my_info_template(),f.my_project_zone_info_template(),f.scope_params());
    }
  }
  public static Binding current(){
    return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a
      &&a.getRequest().getAttribute(ATTRIBUTE) instanceof Binding binding?binding:null;
  }
  public static void bind(HttpServletRequest request,Binding binding){request.setAttribute(ATTRIBUTE,binding);}
  public Binding authenticate(String id,String key,String method,String path){
    if(id==null||!id.matches("[A-Za-z0-9_-]{1,64}")||key==null||key.length()<32||key.length()>256||!method.equals("POST")||!ROUTES.contains(path))throw denied();
    var doc=read();JsonNode entry=doc.root().path("sources");JsonNode selected=null;
    for(var item:entry)if(id.equals(item.path("id").asText()))selected=item;
    if(selected==null||!selected.path("enabled").asBoolean(false)||!active(selected))throw denied();
    String actual=sha(key.getBytes(StandardCharsets.UTF_8));String expected=selected.path("keySha256").asText();
    if(!MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII),expected.getBytes(StandardCharsets.US_ASCII)))throw denied();
    var binding=parse(selected,doc.revision());if(!binding.routes().contains(path))throw denied();return binding;
  }
  private record Document(JsonNode root,String revision){}
  private Document read(){
    try{
      if(file.isBlank())throw denied();var path=Path.of(file);
      if(!path.isAbsolute()||Files.isSymbolicLink(path)||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>262144)throw denied();
      var permissions=Files.getPosixFilePermissions(path,LinkOption.NOFOLLOW_LINKS);
      if(permissions.stream().anyMatch(p->Set.of(PosixFilePermission.OTHERS_READ,PosixFilePermission.OTHERS_WRITE,PosixFilePermission.OTHERS_EXECUTE,PosixFilePermission.GROUP_WRITE,PosixFilePermission.GROUP_EXECUTE).contains(p)))throw denied();
      byte[] bytes=Files.readAllBytes(path);var root=mapper.readTree(bytes);
      if(root.path("version").asInt()!=1||!root.path("sources").isArray()||root.path("sources").size()>1000)throw denied();
      Set<String> ids=new HashSet<>(),owned=new HashSet<>(),hashes=new HashSet<>();String revision=sha(bytes);
      for(var source:root.path("sources")){
        String id=source.path("id").asText();if(!id.matches("[A-Za-z0-9_-]{1,64}")||!ids.add(id))throw denied();
        if(!source.path("enabled").asBoolean(false))continue;
        if(!source.path("keySha256").asText().matches("[a-f0-9]{64}")||!hashes.add(source.path("keySha256").asText()))throw denied();
        var b=parse(source,revision);
        // One server-registered source owns each device identity, even within the same tuple.
        for(var e:b.objects().entrySet())for(var key:e.getValue())if(!owned.add(e.getKey().name()+":"+key))throw denied();
      }
      return new Document(root,revision);
    }catch(ResponseStatusException e){throw e;}catch(Exception e){throw denied();}
  }
  private Binding parse(JsonNode n,String revision){
    String p=n.path("projectId").asText(),r=n.path("regionId").asText(),s=n.path("subsystemCode").asText();
    if(!p.matches("[A-Za-z0-9_-]{1,64}")||!r.matches("[A-Za-z0-9_-]{1,64}")||!s.equals("SCS")||p.equals("LEGACY")||r.equals("UNASSIGNED"))throw denied();
    Set<String> routes=new HashSet<>();n.path("routes").forEach(v->routes.add(v.asText()));if(routes.isEmpty()||!ROUTES.containsAll(routes))throw denied();
    var objects=new EnumMap<ScsCentralScope.Root,Set<String>>(ScsCentralScope.Root.class);var fields=n.path("objects").fields();
    while(fields.hasNext()){
      var entry=fields.next();ScsCentralScope.Root root;try{root=ScsCentralScope.Root.valueOf(entry.getKey());}catch(Exception e){throw denied();}
      if(!Set.of(ScsCentralScope.Root.PERSONNEL,ScsCentralScope.Root.COLLISION,ScsCentralScope.Root.AREA,ScsCentralScope.Root.RUNTIME).contains(root)||!entry.getValue().isArray()||entry.getValue().size()>1000)throw denied();
      Set<String> keys=new HashSet<>();for(var value:entry.getValue()){String key=value.asText();if(!value.isTextual()||!key.matches("[A-Za-z0-9_:-]{1,128}")||!keys.add(key))throw denied();}objects.put(root,keys);
    }
    if(objects.isEmpty())throw denied();try{Instant.parse(n.path("expiresAt").asText());}catch(Exception e){throw denied();}
    return new Binding(n.path("id").asText(),new ScopeTriple(p,r,s),routes,objects,revision,this);
  }
  private boolean active(JsonNode source){try{return Instant.now().isBefore(Instant.parse(source.path("expiresAt").asText()));}catch(Exception e){return false;}}
  private void revalidate(Binding binding){
    var doc=read();if(!doc.revision().equals(binding.revision()))throw denied();
    for(var source:doc.root().path("sources"))if(binding.id().equals(source.path("id").asText())&&source.path("enabled").asBoolean(false)&&active(source))return;
    throw denied();
  }
  public static String sqlObjectFilter(ScsCentralScope.Root root,String alias){
    var binding=current();if(binding==null)return "";binding.revalidate();var keys=binding.objects().getOrDefault(root,Set.of());
    if(keys.isEmpty())return " AND 1=0 ";
    return " AND "+alias+root.key+" IN ("+keys.stream().sorted().map(s->"'"+s+"'").collect(java.util.stream.Collectors.joining(","))+") ";
  }
  private static String sha(byte[] b){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}catch(Exception e){throw new IllegalStateException(e);}}
  static ResponseStatusException denied(){return new ResponseStatusException(HttpStatus.FORBIDDEN,"设备来源未配置、已撤销或未获该对象授权");}
}
