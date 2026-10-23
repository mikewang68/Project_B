package com.bdemo.iam.provisioning;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class IdentityTaskUnknownOrgTest {
  @Test
  void missingLegacyOrganizationDoesNotRollBackAccountRevocation() {
    var db=mock(JdbcTemplate.class);var user=new HashMap<String,Object>();
    user.put("company_code",null);user.put("status","disabled");user.put("deleted",0);
    when(db.queryForMap(anyString(),eq("user-a"))).thenReturn(user);
    var tasks=new IdentityTasks(db,mock(TransactionTemplate.class),new ObjectMapper(),"");
    tasks.sync("user-a","USER_STATUS");
    verify(db).update(startsWith("INSERT INTO iam.identity_task"),eq("user-a"),anyString(),eq(""),eq("DISABLED"));
    // A missing organization remains unmapped; no remote identity is fabricated.
    verify(db).update(startsWith("INSERT INTO iam.iam_audit"),anyString(),anyString(),eq("USER_STATUS"),eq("user-a"));
  }
}
