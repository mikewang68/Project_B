package com.bproject.safety.support.number;

import com.bproject.safety.module.rule.service.RuleNumberGenerator;
import com.bproject.safety.module.fence.service.FenceNumberGenerator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;

/** Global technical allocator, independent of the caller's visible business rows. */
@Component @Profile("server")
public class JdbcConfigurationNumberGenerator implements RuleNumberGenerator,FenceNumberGenerator {
    private final JdbcBusinessNumberStore numbers;
    private final JdbcTemplate jdbc;
    public JdbcConfigurationNumberGenerator(JdbcBusinessNumberStore numbers,JdbcTemplate jdbc){this.numbers=numbers;this.jdbc=jdbc;}
    @Override public String nextRuleNumber(String category){
        String family=switch(category==null?"":category){
            case "设备安全"->"DEV";case "AI识别"->"AI";case "告警策略"->"ALM";
            case "联动策略"->"LNK";case "通知策略"->"NTF";default->"PER";
        };
        return allocate("RULE_"+family,"RULE-"+family+"-",true);
    }
    @Override public String nextFenceNumber(){return allocate("FENCE","FENCE-",false);}
    private String allocate(String type,String prefix,boolean rule){
        // A committed counter prevents concurrent JVMs from allocating the same key.
        // Existing legacy keys are checked without loading or returning their payload.
        for(int i=0;i<100000;i++){
            String id=prefix+String.format(java.util.Locale.ROOT,"%03d",numbers.nextSequence(type,"GLOBAL"));
            String sql=rule?"SELECT count(*) FROM safety.safety_rule WHERE rule_code=?":
                "SELECT count(*) FROM safety.safety_fence WHERE fence_code=?";
            if(Long.valueOf(0).equals(jdbc.queryForObject(sql,Long.class,id)))return id;
        }
        throw new IllegalStateException("Configuration number allocation exceeded collision budget");
    }
}
