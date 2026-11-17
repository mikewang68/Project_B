package com.bproject.ehm.platform.persistence.opengauss;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class MaintenanceTransactionBoundaryTest {
 @Test void partialMaintenanceWritesRollbackOnFailure()throws Throwable{
  var source=new DriverManagerDataSource("jdbc:h2:mem:maintenance-atomic;DB_CLOSE_DELAY=-1","sa","");var jdbc=new JdbcTemplate(source);
  jdbc.execute("CREATE TABLE IF NOT EXISTS test_maintenance(id INT PRIMARY KEY)");jdbc.update("DELETE FROM test_maintenance");
  var aspect=new MaintenanceTransactionBoundary(new DataSourceTransactionManager(source));var point=mock(ProceedingJoinPoint.class);
  when(point.proceed()).thenAnswer(i->{jdbc.update("INSERT INTO test_maintenance VALUES (1)");throw new IllegalStateException("模拟闭环档案保存失败");});
  assertThrows(IllegalStateException.class,()->aspect.atomic(point));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM test_maintenance",Integer.class));
 }
}
