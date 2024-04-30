/*
 * Copyright 2022 Conductor Authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package com.netflix.conductor.core.reconciliation;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.netflix.conductor.core.LifecycleAwareComponent;
import com.netflix.conductor.core.config.ConductorProperties;
import com.netflix.conductor.core.execution.tasks.ExecutionConfig;
import com.netflix.conductor.core.utils.SemaphoreUtil;
import com.netflix.conductor.dao.QueueDAO;
import com.netflix.conductor.metrics.Monitors;

import static com.netflix.conductor.core.utils.Utils.DECIDER_QUEUE;

/**
 * Periodically polls all running workflows in the system and evaluates them for timeouts and/or
 * maintain consistency.
 */
@Component
@ConditionalOnProperty(
        name = "conductor.workflow-reconciler.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class WorkflowReconciler extends LifecycleAwareComponent {

    private final WorkflowSweeper workflowSweeper;
    private final QueueDAO queueDAO;
    private final int sweeperThreadCount;
    private final int sweeperWorkflowPollTimeout;
    ExecutionConfig executionConfig;

    private static final Logger LOGGER = LoggerFactory.getLogger(WorkflowReconciler.class);

    public WorkflowReconciler(
            WorkflowSweeper workflowSweeper, QueueDAO queueDAO, ConductorProperties properties) {
        this.workflowSweeper = workflowSweeper;
        this.queueDAO = queueDAO;
        this.sweeperThreadCount = properties.getSweeperThreadCount();
        this.executionConfig =
                new ExecutionConfig(sweeperThreadCount, "system-workflow-reconciler-%d");
        this.sweeperWorkflowPollTimeout =
                (int) properties.getSweeperWorkflowPollTimeout().toMillis();
        LOGGER.info(
                "WorkflowReconciler initialized with {} sweeper threads",
                properties.getSweeperThreadCount());
    }

    @Scheduled(
            fixedDelayString = "${conductor.sweep-frequency.millis:500}",
            initialDelayString = "${conductor.sweep-frequency.millis:500}")
    public void pollAndSweep() {
        try {
            if (!isRunning()) {
                LOGGER.debug("Component stopped, skip workflow sweep");
                return;
            }

            SemaphoreUtil semaphoreUtil = executionConfig.getSemaphoreUtil();
            int messagesToAcquire = semaphoreUtil.availableSlots();
            if (messagesToAcquire <= 0 || !semaphoreUtil.acquireSlots(messagesToAcquire)) {
                LOGGER.debug("Sweeper no availableSlot");
                return;
            }
            ExecutorService executorService = executionConfig.getExecutorService();
            LOGGER.debug("Sweeper fetch {} message from queue", messagesToAcquire);

            List<String> workflowIds =
                    queueDAO.pop(DECIDER_QUEUE, messagesToAcquire, sweeperWorkflowPollTimeout);
            if (workflowIds.size() > 0) {
                if (workflowIds.size() < messagesToAcquire) {
                    semaphoreUtil.completeProcessing(messagesToAcquire - workflowIds.size());
                }
                LOGGER.debug(
                        "Sweeper processed {} from the decider queue",
                        String.join(",", workflowIds));

                for (String workflowId : workflowIds) {
                    CompletableFuture<Void> workflowCompletableFuture =
                            CompletableFuture.runAsync(
                                    () -> workflowSweeper.sweepAsync(workflowId), executorService);
                    workflowCompletableFuture.whenComplete(
                            (r, e) -> semaphoreUtil.completeProcessing(1));
                }
            } else {
                semaphoreUtil.completeProcessing(messagesToAcquire);
            }
            // NOTE: Disabling the sweeper implicitly disables this metric.
            recordQueueDepth();
        } catch (Exception e) {
            Monitors.error(WorkflowReconciler.class.getSimpleName(), "poll");
            LOGGER.error("Error when polling for workflows", e);
            if (e instanceof InterruptedException) {
                // Restore interrupted state...
                Thread.currentThread().interrupt();
            }
        }
    }

    private void recordQueueDepth() {
        int currentQueueSize = queueDAO.getSize(DECIDER_QUEUE);
        Monitors.recordGauge(DECIDER_QUEUE, currentQueueSize);
    }
}
