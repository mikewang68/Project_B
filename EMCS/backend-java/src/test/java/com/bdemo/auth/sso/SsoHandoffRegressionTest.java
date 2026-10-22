package com.bdemo.auth.sso;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class SsoHandoffRegressionTest {
    @Test void explicitBindingMustUseExistingEmcsUserSchema() throws Exception {
        var db=new SsoTestDatabase();
        db.jdbc.update("INSERT INTO sys_user(user_id,user_name,nick_name,status,del_flag) VALUES(42,'operator','Operator','0','0')");
        db.jdbc.update("INSERT INTO emcs_sso_subject_map(iam_subject,audience,local_user_id,scope_area,iam_domain,iam_zone) VALUES('subject-1','emcs',42,'*','test-domain','ALL')");
        var repository=new EmcsSsoBindingRepository(db.jdbc);
        assertTrue(repository.findActive("subject-1").isPresent(),"An enabled binding to sys_user must be usable");
        db.jdbc.update("UPDATE emcs_sso_subject_map SET enabled=0");
        assertTrue(repository.findActive("subject-1").isEmpty());
        db.jdbc.update("UPDATE emcs_sso_subject_map SET enabled=1");
        db.jdbc.update("UPDATE sys_user SET del_flag='2'");
        assertTrue(repository.findActive("subject-1").isEmpty());
    }

    @Test void defaultScopeDoesNotGrantAllDataAndMigrationCanRollback() throws Exception {
        var db=new SsoTestDatabase();
        db.jdbc.update("INSERT INTO sys_user(user_id,user_name,nick_name,status,del_flag) VALUES(42,'operator','Operator','0','0')");
        db.jdbc.update("INSERT INTO emcs_sso_subject_map(iam_subject,local_user_id,iam_domain,iam_zone) VALUES('subject-1',42,'test-domain','ALL')");
        assertTrue(new EmcsSsoBindingRepository(db.jdbc).findActive("subject-1").isEmpty());
        db.script(java.nio.file.Path.of("sql/rollback/009-emcs-sso-subject-map.sql"));
        assertEquals(1,db.jdbc.queryForObject("SELECT COUNT(*) FROM sys_user",Integer.class));
        db.script(java.nio.file.Path.of("sql/009-emcs-sso-subject-map.sql"));
        assertEquals(0,db.jdbc.queryForObject("SELECT COUNT(*) FROM emcs_sso_subject_map",Integer.class));
    }
}
