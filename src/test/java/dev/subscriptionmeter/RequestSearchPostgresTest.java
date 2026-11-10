package dev.subscriptionmeter;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Run the request search, capture version and export suite against the production query dialect in isolation. */
@EnabledIfEnvironmentVariable(named="AUDIT_INTEGRATION",matches="true")
class RequestSearchPostgresTest extends RequestSearchTest {
    private String schema;
    private JdbcTemplate admin;
    @Override DriverManagerDataSource dataSource(){
        String url=System.getenv().getOrDefault("AUDIT_TEST_JDBC_URL","jdbc:postgresql://127.0.0.1:25432/audit_test");
        if(!url.startsWith("jdbc:postgresql://127.0.0.1:25432/"))throw new IllegalArgumentException("Only isolated audit-test PostgreSQL is allowed");
        schema="request_search_"+UUID.randomUUID().toString().replace("-","");
        admin=new JdbcTemplate(new DriverManagerDataSource(url,"audit_test","local-integration-only"));
        admin.execute("CREATE SCHEMA "+schema);
        return new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,"audit_test","local-integration-only");
    }
    @AfterEach void cleanup(){if(admin!=null&&schema!=null)admin.execute("DROP SCHEMA "+schema+" CASCADE");}
}
