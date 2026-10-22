package com.bdemo.auth.sso;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The database is the only binding authority: absence, disabled users and query failures never fall back. */
@Repository
public class EmcsSsoBindingRepository {
    private final JdbcTemplate jdbc;
    public EmcsSsoBindingRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public record Binding(long localUserId,String localUserName,boolean readOnly,String scopeArea,
                          String company,String org,String domain,String zone) {
        String fingerprint(){
            try {
                String value=localUserId+"\n"+localUserName+"\n"+readOnly+"\n"+scopeArea+"\n"+company+"\n"+org+"\n"+domain+"\n"+zone;
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
            } catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
        }
    }
    public Optional<Binding> findActive(String subject){
        if(subject==null || subject.isBlank())return Optional.empty();
        List<Binding> rows=jdbc.query("""
                SELECT u.user_id,u.user_name,m.read_only,m.scope_area,m.iam_company,m.iam_org,m.iam_domain,m.iam_zone
                FROM emcs_sso_subject_map m JOIN sys_user u ON u.user_id=m.local_user_id
                WHERE m.iam_subject=? AND m.audience='emcs' AND m.enabled=1
                  AND u.status='0' AND u.del_flag='0'
                  AND m.iam_domain<>'' AND m.iam_zone<>''
                  AND (m.scope_area='*' OR EXISTS(SELECT 1 FROM e_area a WHERE a.area_code=m.scope_area))
                """,(rs,n)->new Binding(rs.getLong(1),rs.getString(2),rs.getInt(3)==1,rs.getString(4),
                        rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8)),subject);
        return rows.stream().findFirst();
    }
    void lock(String subject){
        jdbc.queryForList("SELECT iam_subject FROM emcs_sso_subject_map WHERE iam_subject=? AND audience='emcs' FOR UPDATE",subject);
    }
}
