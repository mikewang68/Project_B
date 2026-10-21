package com.bproject.ehm.platform.security;

import com.bproject.ehm.platform.audit.adapter.in.web.IamAuditIdentityFilter;
import com.bproject.ehm.platform.audit.application.EhmAuditRelay;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Service @Profile("server") @ConditionalOnProperty(name="ehm.auth.mode",havingValue="local")
public class LocalAccountService {
    private final JdbcTemplate db;private final TransactionTemplate tx;private final ObjectMapper mapper;private final ObjectProvider<EhmAuditRelay> relay;
    private final String users,sessions,throttle,bootstrap;private final boolean autoDdl;private final long hours,idleMinutes;
    private final String dummyHash=PasswordHash.hash(PasswordHash.token());
    public LocalAccountService(JdbcTemplate db,TransactionTemplate tx,ObjectMapper mapper,ObjectProvider<EhmAuditRelay> relay,
            @Value("${ehm.persistence.schema:ehm}") String schema,@Value("${ehm.persistence.auto-ddl:false}") boolean autoDdl,
            @Value("${ehm.auth.table-prefix:auth_}") String prefix,@Value("${ehm.auth.bootstrap-file:}") String bootstrap,
            @Value("${ehm.auth.session-hours:8}") long hours,@Value("${ehm.auth.idle-minutes:30}") long idleMinutes){
        if(!schema.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")||!prefix.matches("[A-Za-z_][A-Za-z0-9_]{0,30}")||hours<1||hours>24||idleMinutes<1||idleMinutes>120)throw new IllegalArgumentException("Invalid auth configuration");
        this.db=db;this.tx=tx;this.mapper=mapper;this.relay=relay;this.autoDdl=autoDdl;this.bootstrap=bootstrap;this.hours=hours;this.idleMinutes=idleMinutes;
        users=schema+"."+prefix+"user";sessions=schema+"."+prefix+"session";throttle=schema+"."+prefix+"throttle";
    }
    @PostConstruct void initialize() throws Exception {
        if(autoDdl){
            db.execute("CREATE TABLE IF NOT EXISTS "+users+" (user_id VARCHAR(64) PRIMARY KEY,username VARCHAR(32) NOT NULL UNIQUE,display_name VARCHAR(80) NOT NULL,password_hash VARCHAR(256) NOT NULL,role VARCHAR(32) NOT NULL,enabled BOOLEAN NOT NULL,created_at TIMESTAMP WITH TIME ZONE NOT NULL,updated_at TIMESTAMP WITH TIME ZONE NOT NULL)");
            db.execute("CREATE TABLE IF NOT EXISTS "+sessions+" (session_hash VARCHAR(64) PRIMARY KEY,user_id VARCHAR(64) NOT NULL REFERENCES "+users+"(user_id),csrf_token VARCHAR(64) NOT NULL,created_at TIMESTAMP WITH TIME ZONE NOT NULL,expires_at TIMESTAMP WITH TIME ZONE NOT NULL,last_seen TIMESTAMP WITH TIME ZONE NOT NULL)");
            db.execute("CREATE TABLE IF NOT EXISTS "+throttle+" (bucket VARCHAR(64) PRIMARY KEY,failures INTEGER NOT NULL,window_start TIMESTAMP WITH TIME ZONE NOT NULL)");
        }
        db.queryForObject("SELECT COUNT(*) FROM "+sessions+" WHERE 1=0",Long.class);db.queryForObject("SELECT COUNT(*) FROM "+throttle+" WHERE 1=0",Long.class);
        if(db.queryForObject("SELECT COUNT(*) FROM "+users,Long.class)>0)return;
        if(bootstrap.isBlank())throw new IllegalStateException("Empty account store: configure a private EHM_AUTH_BOOTSTRAP_FILE; no default password exists");
        Path file=Path.of(bootstrap);if(Files.size(file)>65536)throw new IllegalStateException("Bootstrap file too large");
        var records=mapper.readTree(Files.readString(file));if(!records.isArray()||records.isEmpty()||records.size()>20)throw new IllegalStateException("Invalid bootstrap accounts");
        var prepared=new ArrayList<Map<String,String>>();boolean admin=false;
        for(var r:records){String role=role(r.path("role").asText());admin|=role.equals("SUPER_ADMIN");prepared.add(Map.of("username",username(r.path("username").asText()),"display",display(r.path("displayName").asText()),"role",role,"hash",PasswordHash.hash(r.path("password").asText())));}
        if(!admin)throw new IllegalStateException("Bootstrap requires an administrator");
        tx.executeWithoutResult(status->{for(var record:prepared)insert(record.get("username"),record.get("display"),record.get("role"),record.get("hash"));});
    }
    public record LoginResult(String token,LocalUser user){}
    public LoginResult login(String name,String password,HttpServletRequest request){
        String normalized=name==null?"":name.strip().toLowerCase(Locale.ROOT);
        if(normalized.length()>128||password==null||password.length()>128)throw rejected();
        String nameBucket=PasswordHash.digest("user\n"+normalized),ipBucket=PasswordHash.digest("ip\n"+request.getRemoteAddr());
        limit(nameBucket,5);limit(ipBucket,30);
        var rows=db.queryForList("SELECT * FROM "+users+" WHERE username=?",normalized);var row=rows.isEmpty()?null:rows.get(0);
        boolean correct=PasswordHash.verify(password,row==null?dummyHash:(String)row.get("password_hash"));
        if(!correct||row==null||!Boolean.TRUE.equals(row.get("enabled"))){fail(nameBucket);fail(ipBucket);throw rejected();}
        String token=PasswordHash.token(),csrf=PasswordHash.token();Instant now=Instant.now();
        LocalUser actor=new LocalUser((String)row.get("user_id"),(String)row.get("username"),(String)row.get("display_name"),(String)row.get("role"),csrf);
        tx.executeWithoutResult(status->{
            db.update("DELETE FROM "+sessions+" WHERE expires_at<=? OR last_seen<=?",Timestamp.from(now),Timestamp.from(now.minusSeconds(idleMinutes*60)));
            db.update("INSERT INTO "+sessions+" (session_hash,user_id,csrf_token,created_at,expires_at,last_seen) VALUES(?,?,?,?,?,?)",PasswordHash.digest(token),actor.userId(),csrf,Timestamp.from(now),Timestamp.from(now.plusSeconds(hours*3600)),Timestamp.from(now));
            db.update("DELETE FROM "+throttle+" WHERE bucket=?",nameBucket);request.setAttribute(IamAuditIdentityFilter.VERIFIED_ACTOR,actor);record(request);
        });
        return new LoginResult(token,actor);
    }
    public LocalUser authenticate(String token){
        if(token==null||!token.matches("[A-Za-z0-9_-]{43}"))return null;Instant now=Instant.now();
        var rows=db.queryForList("SELECT u.*,s.csrf_token FROM "+sessions+" s JOIN "+users+" u ON u.user_id=s.user_id WHERE s.session_hash=? AND s.expires_at>? AND s.last_seen>? AND u.enabled=TRUE",PasswordHash.digest(token),Timestamp.from(now),Timestamp.from(now.minusSeconds(idleMinutes*60)));
        if(rows.isEmpty())return null;var row=rows.get(0);
        if(!Set.of("USER","SUPER_ADMIN").contains(row.get("role")))return null;
        db.update("UPDATE "+sessions+" SET last_seen=? WHERE session_hash=?",Timestamp.from(now),PasswordHash.digest(token));
        return new LocalUser((String)row.get("user_id"),(String)row.get("username"),(String)row.get("display_name"),(String)row.get("role"),(String)row.get("csrf_token"));
    }
    public void logout(String token,HttpServletRequest request){tx.executeWithoutResult(status->{if(token!=null)db.update("DELETE FROM "+sessions+" WHERE session_hash=?",PasswordHash.digest(token));record(request);});}
    public List<Map<String,Object>> list(){return db.queryForList("SELECT user_id,username,display_name,role,enabled,created_at,updated_at FROM "+users+" ORDER BY created_at,user_id").stream().map(this::view).toList();}
    public Map<String,Object> create(String name,String display,String password,String role,HttpServletRequest request){
        String normalized=username(name),label=display(display),selected=role(role),hash=PasswordHash.hash(password);
        try{return tx.execute(status->{String id=insert(normalized,label,selected,hash);record(request);return user(id);});}
        catch(org.springframework.dao.DuplicateKeyException e){throw new ResponseStatusException(HttpStatus.CONFLICT,"用户名已存在");}
    }
    public Map<String,Object> update(String id,String display,String role,boolean enabled,HttpServletRequest request){
        String selected=role(role),label=display(display);var self=IamAuditIdentityFilter.actor(request);
        return tx.execute(status->{
            db.queryForList("SELECT user_id FROM "+users+" WHERE role='SUPER_ADMIN' AND enabled=TRUE ORDER BY user_id FOR UPDATE");
            var old=user(id);
            if(self!=null&&self.getName().equals(id)&&(!enabled||!selected.equals("SUPER_ADMIN")))throw new ResponseStatusException(HttpStatus.CONFLICT,"不能停用或降级当前管理员，请由另一位管理员操作");
            if(old.get("role").equals("SUPER_ADMIN")&&Boolean.TRUE.equals(old.get("enabled"))&&(!enabled||!selected.equals("SUPER_ADMIN"))
                    &&db.queryForObject("SELECT COUNT(*) FROM "+users+" WHERE role='SUPER_ADMIN' AND enabled=TRUE",Long.class)<=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"必须保留至少一名有效超级管理员");
            db.update("UPDATE "+users+" SET display_name=?,role=?,enabled=?,updated_at=? WHERE user_id=?",label,selected,enabled,Timestamp.from(Instant.now()),id);
            if(!enabled||!old.get("role").equals(selected))db.update("DELETE FROM "+sessions+" WHERE user_id=?",id);
            record(request);return user(id);
        });
    }
    public void reset(String id,String password,HttpServletRequest request){
        if(IamAuditIdentityFilter.actor(request)!=null&&IamAuditIdentityFilter.actor(request).getName().equals(id))throw new ResponseStatusException(HttpStatus.CONFLICT,"请使用修改本人密码入口");
        String hash=PasswordHash.hash(password);tx.executeWithoutResult(status->{user(id);db.update("UPDATE "+users+" SET password_hash=?,updated_at=? WHERE user_id=?",hash,Timestamp.from(Instant.now()),id);db.update("DELETE FROM "+sessions+" WHERE user_id=?",id);record(request);});
    }
    public void changePassword(LocalUser actor,String current,String password,HttpServletRequest request){
        var hash=db.queryForObject("SELECT password_hash FROM "+users+" WHERE user_id=?",String.class,actor.userId());
        if(!PasswordHash.verify(current,hash))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"当前密码不正确");
        String next=PasswordHash.hash(password);tx.executeWithoutResult(status->{db.update("UPDATE "+users+" SET password_hash=?,updated_at=? WHERE user_id=?",next,Timestamp.from(Instant.now()),actor.userId());db.update("DELETE FROM "+sessions+" WHERE user_id=?",actor.userId());record(request);});
    }
    private String insert(String name,String display,String role,String hash){String id="local-"+UUID.randomUUID();Instant now=Instant.now();db.update("INSERT INTO "+users+" (user_id,username,display_name,password_hash,role,enabled,created_at,updated_at) VALUES(?,?,?,?,?,TRUE,?,?)",id,name,display,hash,role,Timestamp.from(now),Timestamp.from(now));return id;}
    private Map<String,Object> user(String id){var rows=db.queryForList("SELECT user_id,username,display_name,role,enabled,created_at,updated_at FROM "+users+" WHERE user_id=?",id);if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"账号不存在");return view(rows.get(0));}
    private Map<String,Object> view(Map<String,Object> row){var result=new LinkedHashMap<String,Object>();result.put("id",row.get("user_id"));result.put("username",row.get("username"));result.put("displayName",row.get("display_name"));result.put("role",row.get("role"));result.put("enabled",row.get("enabled"));result.put("createdAt",row.get("created_at"));result.put("updatedAt",row.get("updated_at"));return result;}
    private void record(HttpServletRequest request){var sink=relay.getIfAvailable();if(sink!=null){sink.record(request,"SUCCESS","BUSINESS",0);request.setAttribute(EhmAuditRelay.BUSINESS_RECORDED,true);}}
    private void limit(String bucket,int max){var rows=db.queryForList("SELECT failures FROM "+throttle+" WHERE bucket=? AND window_start>?",bucket,Timestamp.from(Instant.now().minusSeconds(300)));if(!rows.isEmpty()&&((Number)rows.get(0).get("failures")).intValue()>=max)throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"登录失败次数过多，请5分钟后重试");}
    private synchronized void fail(String bucket){Instant now=Instant.now();if(db.update("UPDATE "+throttle+" SET failures=failures+1 WHERE bucket=? AND window_start>?",bucket,Timestamp.from(now.minusSeconds(300)))==1)return;if(db.update("UPDATE "+throttle+" SET failures=1,window_start=? WHERE bucket=?",Timestamp.from(now),bucket)==1)return;try{db.update("INSERT INTO "+throttle+" (bucket,failures,window_start) VALUES(?,1,?)",bucket,Timestamp.from(now));}catch(org.springframework.dao.DuplicateKeyException race){db.update("UPDATE "+throttle+" SET failures=failures+1 WHERE bucket=?",bucket);}}
    private static String username(String value){String text=value==null?"":value.strip().toLowerCase(Locale.ROOT);if(!text.matches("[a-z][a-z0-9_.-]{2,31}"))throw new IllegalArgumentException("Invalid username");return text;}
    private static String display(String value){String text=value==null?"":value.strip();if(text.isBlank()||text.length()>80||text.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid display name");return text;}
    private static String role(String role){if(!Set.of("USER","SUPER_ADMIN").contains(role))throw new IllegalArgumentException("Invalid role");return role;}
    private static ResponseStatusException rejected(){return new ResponseStatusException(HttpStatus.UNAUTHORIZED,"用户名或密码不正确，或账号已停用");}
}
