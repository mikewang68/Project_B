package com.mt.wms.fixture;

import com.bproject.iam.context.*;
import com.mt.wms.auth.WmsPrincipal;
import com.mt.wms.auth.sso.WmsCentralScope;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.sql.Driver;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.client.RestClient;

@EnabledIfEnvironmentVariable(named="WMS_LOCAL_FIXTURE_PORT",matches="[0-9]{2,5}")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.sql.init.mode=never", "server.address=127.0.0.1",
    "wms.sso.enabled=false", "wms.agent.enabled=false", "wms.agent.mail-enabled=false",
    "WMS_PSMS_ENABLED=false"
})
@Import(LocalWarehouseDbFixture.FixtureConfiguration.class)
public abstract class LocalWarehouseDbFixture {
    protected static final String FIXTURE_BEARER="Bearer local-fixture-psms-wms-token-123456789";
    @LocalServerPort private int port;
    @Autowired protected JdbcClient jdbc;
    protected final MockHttpSession session=new MockHttpSession();

    @TestConfiguration
    static class FixtureConfiguration {
        @Bean static BeanFactoryPostProcessor disableScheduledJobs() {
            return factory->{
                var registry=(BeanDefinitionRegistry)factory;
                String processor="org.springframework.context.annotation.internalScheduledAnnotationProcessor";
                if(registry.containsBeanDefinition(processor)) registry.removeBeanDefinition(processor);
            };
        }
        @Bean(destroyMethod="close")
        HikariDataSource localWarehouseDataSource() throws Exception {
            int port=Integer.parseInt(System.getenv("WMS_LOCAL_FIXTURE_PORT"));
            String marker=System.getenv("WMS_LOCAL_FIXTURE_MARKER");
            if(port<1024||marker==null||!marker.matches("[0-9a-f-]{36}"))
                throw new IllegalStateException("Missing isolated fixture marker");
            String url="jdbc:postgresql://127.0.0.1:"+port
                    +"/postgres?sslmode=disable&connectTimeout=3&socketTimeout=5&preferQueryMode=simple";
            // Keep PostgreSQL fixture JDBC separate from the production openGauss driver.
            URL jar=Path.of(System.getenv("WMS_LOCAL_FIXTURE_JDBC_JAR")).toUri().toURL();
            var loader=new URLClassLoader(new URL[]{jar},ClassLoader.getPlatformClassLoader());
            var driver=(Driver)Class.forName("org.postgresql.Driver",true,loader).getDeclaredConstructor().newInstance();
            var source=new SimpleDriverDataSource(driver,url,"postgres","");
            try(var db=source.getConnection();var query=db.prepareStatement("SELECT 1 FROM public.local_warehouse_fixture WHERE marker=?")) {
                query.setString(1,marker);
                try(var result=query.executeQuery()) {
                    if(!result.next()) throw new IllegalStateException("Unexpected fixture database");
                }
            }
            var pool=new HikariDataSource();
            pool.setDataSource(source);
            pool.setMaximumPoolSize(1);
            pool.setMinimumIdle(0);
            pool.setConnectionInitSql("SET ROLE wms_app");
            return pool;
        }
    }

    protected WmsPrincipal counter() { return actor("fixture-counter"); }
    protected WmsPrincipal reviewer() { return actor("fixture-reviewer"); }
    protected RestClient localHttp() {
        return RestClient.builder().baseUrl("http://127.0.0.1:"+port).build();
    }
    protected String runPsms(String phase,Path fixture,String input) throws Exception {
        Path output=fixture.resolve(phase+".log");
        var process=new ProcessBuilder("node",System.getenv("WMS_LOCAL_JOINT_SCRIPT"),phase,
                fixture.resolve("pg-data").toAbsolutePath().toString(),"http://127.0.0.1:"+port)
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            try(var stdin=process.getOutputStream()){if(input!=null) stdin.write(input.getBytes(StandardCharsets.UTF_8));}
            if(!process.waitFor(35,TimeUnit.SECONDS)) throw new IllegalStateException("Isolated PSMS "+phase+" timed out");
            String result=Files.readString(output,StandardCharsets.UTF_8).trim();
            if(process.exitValue()!=0) throw new IllegalStateException(result);
            return result;
        } finally { if(process.isAlive()) process.destroyForcibly(); }
    }
    private WmsPrincipal actor(String name) {
        long company=jdbc.sql("SELECT id FROM auth_company WHERE code='default'").query(Long.class).single();
        long user=jdbc.sql("SELECT id FROM auth_user WHERE company_id=:c AND username=:u").param("c",company).param("u",name).query(Long.class).single();
        return new WmsPrincipal(user,company,"default","Fixture",name,name,List.of("MANAGER"),
            Set.of("tenant:all","inventory:read","inventory:write","stockin:read","stockin:write","stockout:read","stockout:write"),false);
    }

    protected void bind(String menu,String region,boolean readOnly,String... operations) {
        var tuple=new ScopeTriple("FIX_PROJECT",region,"WMS");
        var scope=ScopeSql.build(ScopeSql.Mode.SCOPED,List.of(tuple));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        WmsCentralScope.bind(new UserContext(2,"fixture-subject","fixture","fixture","fixture",
                "L4_OPERATOR",readOnly,"WMS",tuple.projectId(),tuple.regionId(),tuple.subsystemCode(),
                ScopeSql.Mode.SCOPED,List.of(tuple),List.of(tuple),Set.of("fixture-role"),Set.of(menu),
                Map.of(menu,Set.of(operations)),Set.of(),"1",scope.my_info(),scope.my_project_zone_info(),
                scope.my_info_template(),scope.my_project_zone_info_template(),scope.scope_params()));
    }

    protected BigDecimal available(String good) {
        return jdbc.sql("SELECT COALESCE(sum(b.available_qty),0) FROM inv_balance b JOIN wms_good g ON g.id=b.good_id WHERE g.code=:g")
                .param("g",good).query(BigDecimal.class).single();
    }
    protected static BigDecimal qty(String value) { return new BigDecimal(value); }
    @AfterEach void unbind(){RequestContextHolder.resetRequestAttributes();}
}
