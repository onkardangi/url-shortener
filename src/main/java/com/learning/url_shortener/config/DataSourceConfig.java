package com.learning.url_shortener.config;

import org.springframework.context.annotation.Configuration;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * @author onkardangi
 * @date 5/14/26
 * @time 17:27
 */
@Configuration
public class DataSourceConfig {

    // Routing keys — used to select the right datasource
    public static final String PRIMARY = "primary";
    public static final String REPLICA = "replica";

    @Value("${spring.datasource.url}")
    private String primaryUrl;

    @Value("${app.datasource.replica-url:${spring.datasource.url}}")
    private String replicaUrl;

    @Value("${spring.datasource.username}")
    private String username;

    @Value("${spring.datasource.password}")
    private String password;

    @Bean
    public DataSource primaryDataSource() {
        return buildDataSource(primaryUrl, "PrimaryPool");
    }

    @Bean
    public DataSource replicaDataSource() {
        // Falls back to primary URL if replica URL not set.
        // This means local dev (no replica) works without any config change.
        return buildDataSource(replicaUrl, "ReplicaPool");
    }

    @Bean
    @Primary  // Spring uses this as the main DataSource
    public DataSource routingDataSource() {
        Map<Object, Object> dataSources = new HashMap<>();
        dataSources.put(PRIMARY, primaryDataSource());
        dataSources.put(REPLICA, replicaDataSource());

        AbstractRoutingDataSource routing = new AbstractRoutingDataSource() {
            @Override
            protected Object determineCurrentLookupKey() {
                // Route to replica if current transaction is read-only.
                // Spring sets this flag based on @Transactional(readOnly = true).
                boolean isReadOnly = TransactionSynchronizationManager
                        .isCurrentTransactionReadOnly();

                String target = isReadOnly ? REPLICA : PRIMARY;

                // Uncomment to debug routing decisions:
                // System.out.println("Routing to: " + target);

                return target;
            }
        };

        routing.setTargetDataSources(dataSources);
        routing.setDefaultTargetDataSource(primaryDataSource());
        return routing;
    }

    private HikariDataSource buildDataSource(String url, String poolName) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(url);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setPoolName(poolName);
        ds.setMaximumPoolSize(20);
        ds.setMinimumIdle(5);
        ds.setConnectionTimeout(20000);
        ds.setIdleTimeout(300000);
        ds.setMaxLifetime(1200000);
        return ds;
    }
}
