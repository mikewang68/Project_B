package com.bproject.audit.client;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class JobAndLegacyTest {
    @Test void historicalMappingStableAcrossRetryAndNoRawDetail() {
        var row=Map.<String,Object>of("id","l1","created_at",LocalDateTime.of(2026,1,1,8,0),"user_id","u1","action","login","result","fail","detail","password=should-not-forward");
        var first=LegacyLogMapper.map(row,"iam","test","zone1",ZoneId.of("Asia/Shanghai"));
        var second=LegacyLogMapper.map(row,"iam","test","zone1",ZoneId.of("Asia/Shanghai"));
        assertEquals(first,second);assertEquals("FAILED",first.outcome());assertEquals("LOGIN",first.action());assertFalse(first.summary().contains("password"));
        assertEquals(java.time.Instant.parse("2026-01-01T00:00:00Z"),first.occurredAt());
    }
    @Test void backupSuccessRecordsStartAndFinish(){runJob(false);}
    @Test void failedJobKeepsFailureAudit(){runJob(true);}
    private void runJob(boolean failed){
        var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:"+java.util.UUID.randomUUID()+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");var db=new JdbcTemplate(ds);db.execute("CREATE SCHEMA job");
        var mapper=new ObjectMapper().registerModule(new JavaTimeModule());var queue=new JdbcLogOutbox(db,mapper,"job.audit_outbox");queue.initialize();
        var runner=new JobAudit(queue,new TransactionTemplate(new DataSourceTransactionManager(ds)),"ehealth","test","zone1");
        if(failed)assertThrows(IllegalStateException.class,()->runner.run("BACKUP","service-ops","backup1",()->{throw new IllegalStateException("failed");}));
        else assertDoesNotThrow(()->runner.run("BACKUP","service-ops","backup1",()->"done"));
        // Assert durability, not whether the host clock has passed a freshly rounded DB timestamp.
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM job.audit_outbox",Integer.class));
        assertTrue(db.queryForList("SELECT payload FROM job.audit_outbox").stream().anyMatch(row->row.get("payload").toString().contains("\"outcome\":\""+(failed?"FAILED":"SUCCESS")+"\"")));
    }
}
