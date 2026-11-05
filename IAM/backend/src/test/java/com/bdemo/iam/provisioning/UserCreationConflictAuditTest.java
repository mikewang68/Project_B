package com.bdemo.iam.provisioning;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.bdemo.iam.common.BizException;
import com.bdemo.iam.log.OperationLogger;
import com.bdemo.iam.user.UserService;
import com.bdemo.iam.user.UsernameConflictException;
import com.bdemo.iam.user.domain.User;
import com.bdemo.iam.user.dto.UserUpsertRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class UserCreationConflictAuditTest {
  static final String KEY = "creation-request-key-0001";
  UserService users; IdentityTasks tasks; JdbcTemplate db; OperationLogger audit;
  RecordingTransactions transactions; UserCreation creation;
  final UserUpsertRequest request = new UserUpsertRequest("duplicate.user", "name", null, null,
      "not-real-password", List.of(), List.of(), "active", null, null);

  @BeforeEach void setup() {
    users=mock(UserService.class); tasks=mock(IdentityTasks.class); db=mock(JdbcTemplate.class);
    audit=mock(OperationLogger.class); transactions=new RecordingTransactions();
    creation=new UserCreation(users,tasks,db,new TransactionTemplate(transactions),
        new ObjectMapper(),"test-only-idempotency-secret-32-characters");
    ReflectionTestUtils.setField(creation,"operationLogger",audit);
    when(db.queryForList(anyString(),eq(KEY))).thenReturn(List.of());
  }
  User createUser() {
    return users.create("duplicate.user","name",null,null,"not-real-password",List.of(),
        List.of(),"active",null,null);
  }
  void expectAuditAfterRollback(String reason) {
    doAnswer(call -> { assertTrue(transactions.rolledBack,"Warning must survive failed creation transaction"); return null; })
        .when(audit).usernameConflict("duplicate.user",reason);
  }
  @Test void precheckCollisionWarnsAfterRollbackAndDoesNotProvisionIdentity() {
    when(createUser()).thenThrow(new UsernameConflictException());
    expectAuditAfterRollback("PRECHECK");
    assertEquals(409,assertThrows(BizException.class,()->creation.create(KEY,request)).getHttpStatus());
    verify(audit).usernameConflict("duplicate.user","PRECHECK");
    verifyNoInteractions(tasks);
  }
  @Test void racedUsernameUniqueViolationWarnsAfterRollback() {
    when(createUser()).thenThrow(new DuplicateKeyException("synthetic constraint"));
    when(db.queryForObject(anyString(),eq(Integer.class),eq("duplicate.user"))).thenReturn(1);
    expectAuditAfterRollback("UNIQUE_CONSTRAINT");
    assertInstanceOf(UsernameConflictException.class,assertThrows(BizException.class,()->creation.create(KEY,request)));
    verify(audit).usernameConflict("duplicate.user","UNIQUE_CONSTRAINT");
    verifyNoInteractions(tasks);
  }
  @Test void unrelatedUniqueViolationDoesNotInventUsernameCollision() {
    when(createUser()).thenThrow(new DuplicateKeyException("synthetic unrelated constraint"));
    when(db.queryForObject(anyString(),eq(Integer.class),eq("duplicate.user"))).thenReturn(0);
    assertEquals(409,assertThrows(BizException.class,()->creation.create(KEY,request)).getHttpStatus());
    verifyNoInteractions(audit,tasks);
  }
  @Test void unrelatedBusinessConflictDoesNotWarnAsUsernameCollision() {
    when(createUser()).thenThrow(BizException.conflict("scope changed"));
    assertEquals(409,assertThrows(BizException.class,()->creation.create(KEY,request)).getHttpStatus());
    verifyNoInteractions(audit,tasks);
  }
  @Test void normalIdempotentReplayDoesNotCreateOrWarnTwice() {
    User user=new User();user.setId("created-user");
    when(createUser()).thenReturn(user);when(users.get("created-user")).thenReturn(user);
    when(tasks.status("created-user")).thenReturn(Map.of("state","READY"));
    assertSame(user,creation.create(KEY,request));
    var hash=ArgumentCaptor.forClass(String.class);
    verify(db).update(startsWith("INSERT INTO iam.user_creation_request"),eq(KEY),hash.capture(),eq("created-user"));
    when(db.queryForList(anyString(),eq(KEY))).thenReturn(List.of(Map.of("request_hash",hash.getValue(),"user_id","created-user")));
    assertSame(user,creation.create(KEY,request));
    verify(users,times(1)).create("duplicate.user","name",null,null,"not-real-password",List.of(),List.of(),"active",null,null);
    verifyNoInteractions(audit);
  }
  static class RecordingTransactions extends AbstractPlatformTransactionManager {
    boolean rolledBack;
    @Override protected Object doGetTransaction(){return new Object();}
    @Override protected void doBegin(Object transaction,TransactionDefinition definition){rolledBack=false;}
    @Override protected void doCommit(DefaultTransactionStatus status){}
    @Override protected void doRollback(DefaultTransactionStatus status){rolledBack=true;}
  }
}
