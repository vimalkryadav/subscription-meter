package dev.subscriptionmeter;

import dev.subscriptionmeter.messaging.JdbcAuditProjector;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/** Captured data is never deleted: 0/0 retention must not expire anything. */
class AuditRetentionTest {
    @Test void keepForeverIsValidAndNeverExpiresIncompleteCaptures(){
        var ds=new DriverManagerDataSource("jdbc:h2:mem:retention-"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE","sa","");
        var jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE audit_operations(operation_id VARCHAR(160) PRIMARY KEY,request_id VARCHAR(36) NOT NULL,kind VARCHAR(16) NOT NULL,observed_at TIMESTAMP WITH TIME ZONE NOT NULL,part_count INTEGER NOT NULL,payload_bytes INTEGER NOT NULL,sha256 VARCHAR(64) NOT NULL,completed BOOLEAN NOT NULL DEFAULT FALSE,discard_reason VARCHAR(32),completed_at TIMESTAMP WITH TIME ZONE)");
        jdbc.execute("CREATE TABLE audit_payload_parts(operation_id VARCHAR(160) NOT NULL REFERENCES audit_operations(operation_id),part_number INTEGER NOT NULL,data TEXT NOT NULL,PRIMARY KEY(operation_id,part_number))");
        jdbc.update("INSERT INTO audit_operations(operation_id,request_id,kind,observed_at,part_count,payload_bytes,sha256,completed) VALUES('old:BEGIN','old','BEGIN',TIMESTAMP '2020-01-01 00:00:00',2,10,'x',FALSE)");
        jdbc.update("INSERT INTO audit_payload_parts(operation_id,part_number,data) VALUES('old:BEGIN',0,'AAAA')");
        var projector=new JdbcAuditProjector(jdbc,new TransactionTemplate(new DataSourceTransactionManager(ds)),0,0);
        projector.expireIncomplete();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_payload_parts",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT completed FROM audit_operations",Boolean.class)).isFalse();
    }
    @Test void partialKeepForeverIsRejected(){
        assertThatThrownBy(()->new JdbcAuditProjector(null,null,0,90)).isInstanceOf(IllegalArgumentException.class);
    }
}
