package dev.subscriptionmeter;

import java.util.UUID;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** Opt-in against an isolated test PostgreSQL database, never the application database. */
@EnabledIfEnvironmentVariable(named="CONTROL_USAGE_POSTGRES_TEST_URL",matches="jdbc:postgresql:.*")
class PostgresCoordinationTest extends CoordinationTest {
    @Override Fixture fixture() {
        String url=System.getenv("CONTROL_USAGE_POSTGRES_TEST_URL");
        String schema="test_"+UUID.randomUUID().toString().replace("-","");
        var admin=new JdbcTemplate(new DriverManagerDataSource(url,"meter",""));
        admin.execute("CREATE SCHEMA "+schema);
        var ds=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,"meter","");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        return new Fixture(new JdbcTemplate(ds),new DataSourceTransactionManager(ds));
    }
}
