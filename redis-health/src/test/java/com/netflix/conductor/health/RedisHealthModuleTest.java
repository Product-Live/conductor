package com.netflix.conductor.health;

import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Key;
import com.google.inject.Provides;
import com.netflix.archaius.ConfigProxyFactory;
import com.netflix.archaius.guice.ArchaiusModule;
import com.netflix.conductor.dao.redis.JedisMock;
import com.netflix.governator.InjectorBuilder;
import com.netflix.governator.LifecycleInjector;
import com.netflix.governator.event.ApplicationEventDispatcher;
import com.netflix.governator.event.guava.GuavaApplicationEventModule;
import com.netflix.runtime.health.api.HealthCheckAggregator;
import com.netflix.runtime.health.api.HealthCheckStatus;
import com.netflix.runtime.health.api.HealthIndicator;
import com.netflix.runtime.health.core.SimpleHealthCheckAggregator;
import com.netflix.runtime.health.core.caching.DefaultCachingHealthCheckAggregator;
import com.netflix.runtime.health.guice.HealthAggregatorConfiguration;
import com.netflix.runtime.health.guice.HealthModule;
import org.junit.Assert;
import org.junit.Test;
import redis.clients.jedis.commands.JedisCommands;

import javax.inject.Provider;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public class RedisHealthModuleTest {

    private class JedisModule extends AbstractModule {

        @Override
        protected void configure() {
            bind(JedisCommands.class).to(JedisMock.class);
        }

    }

    @Test
    public void testNoIndicators() throws InterruptedException, ExecutionException {
        LifecycleInjector injector = InjectorBuilder.fromModules(new HealthModule(), new ArchaiusModule()).createInjector();

        HealthCheckAggregator aggregator = injector.getInstance(HealthCheckAggregator.class);
        Assert.assertNotNull(aggregator);
        HealthCheckStatus healthCheckStatus = aggregator.check().get();
        Assert.assertTrue(healthCheckStatus.isHealthy());
        Assert.assertEquals(0, healthCheckStatus.getHealthResults().size());
    }


    @Test
    public void testHealthyIndicators() throws InterruptedException, ExecutionException {
        LifecycleInjector injector = InjectorBuilder.fromModules(new JedisModule(), new RedisHealthModule(), new ArchaiusModule()).createInjector();
        HealthCheckAggregator aggregator = injector.getInstance(HealthCheckAggregator.class);
        Assert.assertNotNull(aggregator);
        HealthCheckStatus healthCheckStatus = aggregator.check().get();
        Assert.assertTrue(healthCheckStatus.isHealthy());
        Assert.assertEquals(1, healthCheckStatus.getHealthResults().size());
    }

    @Test
    public void testUnHealthyIndicators() throws InterruptedException, ExecutionException {
        LifecycleInjector injector = InjectorBuilder.fromModules(new RedisHealthModule(), new ArchaiusModule()).createInjector();
        HealthCheckAggregator aggregator = injector.getInstance(HealthCheckAggregator.class);
        Assert.assertNotNull(aggregator);
        HealthCheckStatus healthCheckStatus = aggregator.check().get();
        Assert.assertTrue(healthCheckStatus.isHealthy());
        Assert.assertEquals(1, healthCheckStatus.getHealthResults().size());
    }

}
