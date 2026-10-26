package com.bdemo.iam.navigation.service;

import com.bdemo.iam.common.BizException;
import com.bdemo.iam.log.OperationLogger;
import com.bdemo.iam.navigation.model.NavigationAppEntity;
import com.bdemo.iam.security.JwtAuthFilter;
import com.bdemo.iam.security.LoginUser;
import com.bdemo.iam.sso.SsoServiceIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NavigationAddressPublicationService {
  public record Address(String domainName,String publicScheme,Integer publicPort,
      String originScheme,String originIp,Integer originPort,String modulePath) {
    public String baseUrl() {
      var app=new NavigationAppEntity(); app.setDomainName(domainName);app.setPublicScheme(publicScheme);
      app.setPublicPort(publicPort);app.setOriginScheme(originScheme);app.setOriginIp(originIp);
      app.setOriginPort(originPort);app.setModulePath(modulePath);
      try {String result=NavigationAddressResolver.resolve(app);if(result==null)throw new IllegalArgumentException();return result;}
      catch(IllegalArgumentException invalid){throw new BizException(400,40001,"访问地址配置无效");}
    }
  }
  private static final Set<String> APPS=Set.of("IAM","SYS","WMS","PSMS","SCS","EHMS","EMCS","TRUST");
  private final JdbcTemplate db;private final ObjectMapper json;private final SsoServiceIdentity services;
  private final OperationLogger audit;private final Set<String> allowedSuffixes;
  private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
      .followRedirects(HttpClient.Redirect.NEVER).build();
  public NavigationAddressPublicationService(JdbcTemplate db,ObjectMapper json,SsoServiceIdentity services,
      OperationLogger audit,@Value("${iam.navigation.address.allowed-host-suffixes:}")String suffixes) {
    this.db=db;this.json=json;this.services=services;this.audit=audit;
    allowedSuffixes=new HashSet<>();for(String value:suffixes.split(","))if(!value.isBlank())allowedSuffixes.add(value.trim().toLowerCase(Locale.ROOT));
  }
  private String app(String value){String key=value==null?"":value.toUpperCase(Locale.ROOT);
    if(!APPS.contains(key))throw new BizException(400,40001,"未知系统");return key;}
  private LoginUser administrator(){var user=JwtAuthFilter.currentUser();
    if(user==null||!user.isSuperAdmin()||user.isReadOnly())throw new BizException(403,40301,"仅平台全权管理员可管理访问地址");return user;}
  private Map<String,Object> current(String app,boolean lock){
    var rows=db.queryForList("SELECT app_code,domain_name,public_scheme,public_port,origin_scheme,origin_ip,origin_port,module_path,address_version FROM iam.iam_navigation_app WHERE app_code=? AND valid=1"+(lock?" FOR UPDATE":""),app);
    if(rows.size()!=1)throw new BizException(404,40401,"系统地址不存在");return rows.get(0);
  }
  private Integer port(Object value){return value==null?null:((Number)value).intValue();}
  private Address address(Map<String,Object> row){return new Address((String)row.get("domain_name"),(String)row.get("public_scheme"),port(row.get("public_port")),
      (String)row.get("origin_scheme"),(String)row.get("origin_ip"),port(row.get("origin_port")),(String)row.get("module_path"));}
  private String encode(Address value){try{return json.writeValueAsString(value);}catch(Exception invalid){throw new IllegalArgumentException(invalid);}}
  private Address decode(Object value){try{return json.readValue(value.toString(),Address.class);}catch(Exception invalid){throw new BizException(500,50001,"地址版本记录无法读取");}}
  public List<Map<String,Object>> list(){administrator();return db.queryForList("SELECT app_code,domain_name,public_scheme,public_port,origin_scheme,origin_ip,origin_port,module_path,address_version FROM iam.iam_navigation_app WHERE valid=1 AND in_nav_scope=1 ORDER BY sort_order");}
  public List<Map<String,Object>> history(String code){administrator();return db.queryForList("SELECT * FROM iam.iam_navigation_address_revision WHERE app_code=? ORDER BY created_at DESC",app(code));}
  private Map<String,Object> revision(String id){var rows=db.queryForList("SELECT * FROM iam.iam_navigation_address_revision WHERE revision_id=?",id);
    if(rows.size()!=1)throw new BizException(404,40401,"地址版本不存在");return rows.get(0);}
  private void lockRevision(String id){
    if(db.queryForList("SELECT revision_id FROM iam.iam_navigation_address_revision WHERE revision_id=? FOR UPDATE",id).size()!=1)
      throw new BizException(404,40401,"地址版本不存在");
  }
  @Transactional
  public Map<String,Object> draft(String code,long expected,Address proposed){
    var user=administrator();String key=app(code);var active=current(key,false);
    if(((Number)active.get("address_version")).longValue()!=expected)throw new BizException(409,40901,"地址已被更新，请刷新后重试");
    if(proposed==null)throw new BizException(400,40001,"缺少地址配置");
    // Validate the origin transport even when the browser address uses a domain.
    new Address("",null,null,proposed.originScheme(),proposed.originIp(),proposed.originPort(),proposed.modulePath()).baseUrl();
    URI candidate=URI.create(proposed.baseUrl());URI previous=URI.create(address(active).baseUrl());
    String host=candidate.getHost().toLowerCase(Locale.ROOT);
    boolean permitted=host.equals(previous.getHost())||host.equals(active.get("origin_ip"))
        ||allowedSuffixes.stream().anyMatch(s->host.equals(s)||host.endsWith("."+s));
    if(!permitted)throw new BizException(400,40001,"域名不在部署端登记的发布范围内");
    if(!candidate.getScheme().equals("https"))throw new BizException(400,40001,"统一登录访问入口必须使用HTTPS，HTTP回源请单独配置");
    String id=UUID.randomUUID().toString();db.update("INSERT INTO iam.iam_navigation_address_revision(revision_id,app_code,base_version,address_json,state,created_by) VALUES(?,?,?,?,?,?)",
        id,key,expected,encode(proposed),"DRAFT",user.getUserId());
    log(user,"draft",key,"创建访问地址草稿 "+id);return revision(id);
  }
  /** Public readiness challenge proves the endpoint has this application's service key; no key is sent to a proposed host. */
  private HttpResponse<byte[]> fetchProbe(URI target,int limit)throws java.io.IOException,InterruptedException{
    var request=HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(8))
        .header("User-Agent","Mozilla/5.0 ProjectB-Address-Readiness").GET().build();
    for(int attempt=0;;attempt++){
      try{
        var response=http.send(request,info->new BoundedReadinessBody(limit));
        if(response.statusCode()<500||attempt==2)return response;
      }catch(java.io.IOException transientFailure){
        // Certificate failures never become an insecure fallback or an indefinite retry.
        if(transientFailure instanceof javax.net.ssl.SSLException||attempt==2)throw transientFailure;
      }
      Thread.sleep(200L*(attempt+1));
    }
  }
  public void probe(String code,Address proposed){
    try {
      String aud=app(code).toLowerCase(Locale.ROOT),nonce=UUID.randomUUID().toString();
      URI target=URI.create(proposed.baseUrl()).resolve("/sso/readiness?challenge="+nonce);
      var response=fetchProbe(target,16384);
      byte[] bytes=response.body();
      if(response.statusCode()!=200||bytes.length>16384)throw new IllegalArgumentException();
      var body=json.readTree(bytes);String key=services.tokenFor(aud);
      if(key==null||!aud.equals(body.path("audience").asText())||!nonce.equals(body.path("challenge").asText()))throw new IllegalArgumentException();
      var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
      byte[] expected=mac.doFinal((aud+"\n"+nonce).getBytes(StandardCharsets.UTF_8));
      if(!MessageDigest.isEqual(expected,Base64.getUrlDecoder().decode(body.path("proof").asText())))throw new IllegalArgumentException();
      var page=fetchProbe(URI.create(proposed.baseUrl()),1024*1024);
      if(page.statusCode()!=200||!page.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT).contains("text/html"))
        throw new IllegalArgumentException();
    }catch(Exception failure){if(failure instanceof InterruptedException)Thread.currentThread().interrupt();
      throw new BizException(502,50201,"地址校验失败：请检查DNS、TLS、Cloudflare回源、目标系统就绪接口及模块页面路径");}
  }
  @Transactional
  public Map<String,Object> validate(String id){administrator();lockRevision(id);var row=revision(id);
    if(!Set.of("DRAFT","VALIDATED").contains(row.get("state")))throw new BizException(409,40901,"该版本不可再次校验");
    try{probe(row.get("app_code").toString(),decode(row.get("address_json")));}
    catch(BizException failure){db.update("UPDATE iam.iam_navigation_address_revision SET state='FAILED',validated_at=CURRENT_TIMESTAMP,validation_message=? WHERE revision_id=?",failure.getMessage(),id);return revision(id);}
    db.update("UPDATE iam.iam_navigation_address_revision SET state='VALIDATED',validated_at=CURRENT_TIMESTAMP,validation_message='DNS/TLS/application readiness and module page verified' WHERE revision_id=?",id);
    return revision(id);
  }
  @Transactional
  public Map<String,Object> publish(String id){var user=administrator();lockRevision(id);var draft=revision(id);
    if(!"VALIDATED".equals(draft.get("state")))throw new BizException(409,40901,"请先完成地址校验");
    String code=draft.get("app_code").toString();Address next=decode(draft.get("address_json"));probe(code,next);
    var active=current(code,true);long version=((Number)active.get("address_version")).longValue();
    if(((Number)draft.get("base_version")).longValue()!=version)throw new BizException(409,40901,"地址版本已变化，请重新创建草稿");
    if(db.queryForObject("SELECT count(*) FROM iam.iam_navigation_address_revision WHERE app_code=? AND published_version=?",Long.class,code,version)==0)
      db.update("INSERT INTO iam.iam_navigation_address_revision(revision_id,app_code,base_version,published_version,address_json,state,created_by,published_at) VALUES(?,?,?,?,?,'PUBLISHED',?,CURRENT_TIMESTAMP)",UUID.randomUUID().toString(),code,version,version,encode(address(active)),user.getUserId());
    db.update("UPDATE iam.iam_navigation_address_revision SET state='SUPERSEDED',retire_after=CURRENT_TIMESTAMP+INTERVAL '180 seconds' WHERE app_code=? AND published_version=?",code,version);
    db.update("UPDATE iam.iam_navigation_app SET domain_name=?,public_scheme=?,public_port=?,origin_scheme=?,origin_ip=?,origin_port=?,module_path=?,address_version=address_version+1 WHERE app_code=? AND address_version=?",
        next.domainName(),next.publicScheme(),next.publicPort(),next.originScheme(),next.originIp(),next.originPort(),next.modulePath(),code,version);
    db.update("UPDATE iam.iam_navigation_address_revision SET state='PUBLISHED',published_version=?,published_at=CURRENT_TIMESTAMP WHERE revision_id=?",version+1,id);
    log(user,"publish",code,"发布访问地址版本 "+(version+1)+"："+encode(address(active))+" → "+encode(next));return runtime(code,null);
  }
  @Transactional
  public Map<String,Object> rollback(String id){administrator();var old=revision(id);
    if(old.get("published_version")==null)throw new BizException(400,40001,"只能回退到曾发布的版本");
    String code=old.get("app_code").toString();long current=((Number)current(code,false).get("address_version")).longValue();
    String draftId=draft(code,current,decode(old.get("address_json"))).get("revision_id").toString();validate(draftId);return publish(draftId);
  }
  public Map<String,Object> runtime(String code,Long requestedVersion){String key=app(code);var current=current(key,false);
    long version=((Number)current.get("address_version")).longValue();Address configured=address(current);
    if(requestedVersion!=null&&requestedVersion!=version){var rows=db.queryForList("SELECT address_json FROM iam.iam_navigation_address_revision WHERE app_code=? AND published_version=? AND state='SUPERSEDED' AND retire_after>CURRENT_TIMESTAMP",key,requestedVersion);
      if(rows.size()!=1)throw new BizException(409,40901,"该地址事务版本已过期，请重新进入功能");configured=decode(rows.get(0).get("address_json"));version=requestedVersion;}
    String base=configured.baseUrl();URI uri=URI.create(base);
    return Map.of("appCode",key,"version",version,"baseUrl",base,"origin",uri.getScheme()+"://"+uri.getRawAuthority(),"modulePath",uri.getPath());
  }
  private void log(LoginUser user,String action,String app,String detail){audit.record(user.getUsername(),user.getUserId(),"operation","navigation-address",action,app,detail,"POST","/navigation/addresses","","success");}
}
