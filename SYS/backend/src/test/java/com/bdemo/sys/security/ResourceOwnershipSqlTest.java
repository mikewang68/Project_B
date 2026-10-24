package com.bdemo.sys.security;
import static org.junit.jupiter.api.Assertions.*;
import com.bdemo.sys.dict.mapper.DictMapper;
import com.bdemo.sys.config.mapper.ConfigMapper;
import com.bdemo.sys.log.mapper.LogMapper;
import java.util.*;
import org.apache.ibatis.builder.annotation.MapperAnnotationBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class ResourceOwnershipSqlTest {
  Configuration config=new Configuration();
  @BeforeEach void setup(){for(var mapper:List.of(DictMapper.class,ConfigMapper.class,LogMapper.class))new MapperAnnotationBuilder(config,mapper).parse();}
  @AfterEach void cleanup(){SecurityContextHolder.clearContext();}
  String sql(Class<?> mapper,String method){return config.getMappedStatement(mapper.getName()+"."+method).getBoundSql(new HashMap<>()).getSql();}
  @Test void scopedReadsAndWritesHaveTheSameOwnerPredicate(){var user=new LoginUser("sys","sys","SYS",false,Set.of());SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,null,List.of()));for(var m:List.of("selectTypes","selectTypeById","selectItems","selectItemById","updateItem","logicDeleteType"))assertTrue(sql(DictMapper.class,m).contains("owner_app_code='SYS'"),m);assertTrue(sql(ConfigMapper.class,"selectAll").contains("owner_app_code='SYS'"));assertTrue(sql(ConfigMapper.class,"updateValue").contains("owner_app_code='SYS'"));}
  @Test void countExportAndStatisticsCannotLeakOtherSystems(){var user=new LoginUser("sys","sys","SYS",false,Set.of());SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,null,List.of()));for(var m:List.of("selectLogs","countLogs","groupByResult","groupByModule","groupByUser","selectForDiagnosis"))assertTrue(sql(LogMapper.class,m).contains("owner_app_code='SYS'"),m);}
  @Test void platformReadsRetainExistingVisibility(){var user=new LoginUser("root","root","Root",true,Set.of());SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,null,List.of()));assertFalse(sql(DictMapper.class,"selectTypes").contains("owner_app_code='SYS'"));user.setSuperAdmin(false);user.setGlobalRead(true);assertFalse(sql(LogMapper.class,"selectLogs").contains("owner_app_code='SYS'"));}
}
