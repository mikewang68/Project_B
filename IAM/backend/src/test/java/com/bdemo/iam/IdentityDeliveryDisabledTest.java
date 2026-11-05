package com.bdemo.iam;

import com.bdemo.iam.provisioning.IdentityTasks;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import static org.mockito.Mockito.*;

class IdentityDeliveryDisabledTest {
  @Test void anIsolatedRestoreCanValidateOrganizationsWithoutLeasingOrSendingIdentityTasks() {
    var db=mock(JdbcTemplate.class);
    var tx=mock(TransactionTemplate.class);
    var tasks=new IdentityTasks(db,tx,new ObjectMapper(),"/private/organization-config.json");
    ReflectionTestUtils.setField(tasks,"deliveryEnabled",false);
    tasks.recover();
    tasks.process("existing-restored-subject");
    tasks.process("new-candidate-subject",System.nanoTime()+1_000_000_000L);
    verifyNoInteractions(db,tx);
  }
}
