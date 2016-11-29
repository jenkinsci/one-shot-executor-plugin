/*
 * The MIT License
 *
 *  Copyright (c) 2016, CloudBees, Inc.
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in
 *  all copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *  THE SOFTWARE.
 *
 */

package org.jenkinsci.plugins.oneshot;

import hudson.Extension;
import hudson.ExtensionList;
import hudson.model.Label;
import hudson.model.LoadStatistics;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import jenkins.model.Jenkins;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.logging.Logger;

import static hudson.slaves.NodeProvisioner.StrategyDecision.CONSULT_REMAINING_STRATEGIES;
import static hudson.slaves.NodeProvisioner.StrategyDecision.PROVISIONING_COMPLETED;
import static java.util.logging.Level.FINE;

/**
 * Replacement for the default {@link NodeProvisioner} to create build agent as soon as a build start.
 * This removes the need to hack {@link hudson.model.LoadStatistics#CLOCK} to provision agent in a reasonable time.
 */
@Extension
public class OneShotProvisionerStrategy extends NodeProvisioner.Strategy {

    private static final Logger LOGGER = Logger.getLogger(OneShotProvisionerStrategy.class.getName());

    private volatile boolean enabled =
            Boolean.parseBoolean(
                    System.getProperty(OneShotProvisionerStrategy.class.getName()+".enabled", "true")
            );

    /**
     * Returns the strategy singleton for the current Jenkins instance.
     *
     * @return the strategy singleton for the current Jenkins instance or {@code null}
     */
    @CheckForNull
    public static OneShotProvisionerStrategy getInstance() {
        return ExtensionList.lookup(NodeProvisioner.Strategy.class).get(OneShotProvisionerStrategy.class);
    }

    /**
     * Returns {@code true} if this strategy is enabled for the current Jenkins instance.
     *
     * @return {@code true} if this strategy is enabled for the current Jenkins instance.
     */
    public static boolean isEnabled() {
        OneShotProvisionerStrategy strategy = getInstance();
        return strategy != null && strategy.enabled;
    }

    /**
     * Sets whether this strategy is enabled for the current Jenkins instance. Useful to disable this strategy
     * in groovy console
     *
     * @param enabled if {@code true} then the strategy will be enabled - including injecting it in the list of
     *                strategies if necessary, if {@code false} then the strategy will be disabled.
     */
    public static void setEnabled(boolean enabled) {
        OneShotProvisionerStrategy strategy = getInstance();
        if (strategy == null && enabled) {
            strategy = new OneShotProvisionerStrategy();
            ExtensionList.lookup(NodeProvisioner.Strategy.class).add(0, strategy);
        }
        if (strategy != null) {
            strategy.enabled = enabled;
        }
    }

    @Nonnull
    @Override
    public NodeProvisioner.StrategyDecision apply(@Nonnull NodeProvisioner.StrategyState strategyState) {
        if (Jenkins.getInstance().isQuietingDown()) {
            return CONSULT_REMAINING_STRATEGIES;
        }

        NodeProvisioner.StrategyDecision currentState = CONSULT_REMAINING_STRATEGIES;
        for (Cloud cloud : getOneShotClouds()) {
            currentState = applyForCloud(strategyState, cloud);
            if (currentState == PROVISIONING_COMPLETED) break;
        }
        return currentState;
    }

    /**
     * Here we determine number of additional agents to provision. As we only consider one-shot executors, we don't need
     * to do some complex load estimate like {@link NodeProvisioner.StandardStrategyImpl} does, just ensure we consider
     * the currently provisioning agents while counting.
     */
    private NodeProvisioner.StrategyDecision applyForCloud(@Nonnull NodeProvisioner.StrategyState strategyState, Cloud cloud) {
        final Label label = strategyState.getLabel();

        if (!cloud.canProvision(label)) {
            return CONSULT_REMAINING_STRATEGIES;
        }

        LoadStatistics.LoadStatisticsSnapshot snapshot = strategyState.getSnapshot();
        int availableCapacity =
              snapshot.getAvailableExecutors()
            + snapshot.getConnectingExecutors()
            + strategyState.getPlannedCapacitySnapshot();

        int currentDemand = snapshot.getQueueLength();
        LOGGER.log(FINE, "Available capacity={0}, currentDemand={1}",
                new Object[]{availableCapacity, currentDemand});

        if (availableCapacity < currentDemand) {
            Collection<NodeProvisioner.PlannedNode> plannedNodes = cloud.provision(label, currentDemand - availableCapacity);
            LOGGER.log(FINE, "Planned {0} new nodes", plannedNodes.size());
            strategyState.recordPendingLaunches(plannedNodes);
            availableCapacity += plannedNodes.size();
            LOGGER.log(FINE, "After provisioning, available capacity={0}, currentDemand={1}",
                    new Object[]{availableCapacity, currentDemand});
        }

        if (availableCapacity >= currentDemand) {
            LOGGER.log(FINE, "Provisioning completed");
            return PROVISIONING_COMPLETED;
        } else {
            LOGGER.log(FINE, "Provisioning not complete, consulting remaining strategies");
            return CONSULT_REMAINING_STRATEGIES;
        }
    }

    /**
     * Gets all {@link Cloud} instances.
     * @return a list of {@link Cloud} instances.
     */
    private static List<Cloud> getOneShotClouds() {
        final Jenkins jenkins = Jenkins.getInstance();
        List<Cloud> clouds = new ArrayList<>();
        for (Cloud cloud : jenkins.clouds) {
            if (cloud instanceof OneShotCloud) {
                clouds.add(cloud);
            }
        }
        return clouds;
    }
}
