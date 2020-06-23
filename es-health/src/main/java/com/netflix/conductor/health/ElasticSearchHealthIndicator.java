package com.netflix.conductor.health;

import com.netflix.runtime.health.api.Health;
import com.netflix.runtime.health.api.HealthIndicator;
import com.netflix.runtime.health.api.HealthIndicatorCallback;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.health.ClusterHealthStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;

public class ElasticSearchHealthIndicator implements HealthIndicator {
    private static final Logger LOGGER = LoggerFactory.getLogger(ElasticSearchHealthIndicator.class);

    private final Client esClient;

    @Inject
    public ElasticSearchHealthIndicator(Client esClient) {
        this.esClient = esClient;
        LOGGER.debug("Health Indicator is Ready !");
    }

    @Override
    public void check(HealthIndicatorCallback healthIndicatorCallback) {
        LOGGER.debug("Checking Health");
        try {
            ClusterHealthResponse healths = this.esClient.admin().cluster().prepareHealth().get();

            if (healths.isTimedOut()) {
                healthIndicatorCallback.inform(Health.unhealthy().withDetail("timed_out", "").build());
            }

            // TODO Anything needed that define as Healthy for your need
            if (healths.getStatus().equals(ClusterHealthStatus.GREEN)
                || healths.getStatus().equals(ClusterHealthStatus.YELLOW)) {
                healthIndicatorCallback.inform(Health.healthy().build());
            } else {
                healthIndicatorCallback.inform(Health.unhealthy().withDetail("cluster_health_status", healths.getStatus().name()).build());
            }
        } catch (Exception e) {
            healthIndicatorCallback.inform(Health.unhealthy().withException(e).build());
        }
    }
}
