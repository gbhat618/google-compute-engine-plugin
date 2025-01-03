/*
 * Copyright 2020 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.jenkins.plugins.computeengine;

import static java.util.Collections.emptyList;

import com.google.api.services.compute.model.Instance;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;

import hudson.Extension;
import hudson.model.PeriodicWork;
import hudson.model.Slave;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;

/** Periodically checks if there are no lost nodes in GCP. If it finds any they are deleted. */
@Extension
@Symbol("cleanLostNodesWork")
public class CleanLostNodesWork extends PeriodicWork {
    protected final Logger logger = Logger.getLogger(getClass().getName());
    public static final String NODE_IN_USE_LABEL_KEY = "jenkins_node_in_use";
    public static final String NODE_TYPE_LABEL_KEY =  "jenkins_node_type";
    public static final String NODE_TYPE_LABEL_VALUE = "cloud_agent";
    public static final long RECURRENCE_PERIOD = Long.parseLong(
        System.getProperty("jenkins.cloud.gcp.cleanLostNodesWork.recurrencePeriod", String.valueOf(HOUR)));
    private static final int ORPHAN_MULTIPLIER = 3;

    /** {@inheritDoc} */
    @Override
    public long getRecurrencePeriod() {
        return RECURRENCE_PERIOD;
    }

    /** {@inheritDoc} */
    @VisibleForTesting
    @Override
    public void doRun() {
        logger.log(Level.FINEST, "Starting clean lost nodes worker");
        getClouds().forEach(this::cleanCloud);
    }

    private void cleanCloud(ComputeEngineCloud cloud) {
        logger.log(Level.FINEST, "Cleaning cloud " + cloud.getCloudName());
        List<Instance> remoteInstances = findRemoteInstances(cloud);
        Set<String> localInstances = findLocalInstances(cloud);
        updateLocalInstancesLabel(localInstances, cloud);
        remoteInstances.stream().filter(this::isOrphaned).forEach(remote -> terminateInstance(remote, cloud));
    }

    private boolean isOrphaned(Instance remote) {
        String nodeInUseTs = remote.getLabels().get(NODE_IN_USE_LABEL_KEY);

        if (nodeInUseTs == null) {
            return false;
        }

        return Long.parseLong(nodeInUseTs) < System.currentTimeMillis() - RECURRENCE_PERIOD * ORPHAN_MULTIPLIER;
    }

    private void terminateInstance(Instance remote, ComputeEngineCloud cloud) {
        String instanceName = remote.getName();
        logger.log(Level.INFO, "Remote instance " + instanceName + " not found locally, removing it");
        try {
            cloud.getClient().terminateInstanceAsync(cloud.getProjectId(), remote.getZone(), instanceName);
        } catch (IOException ex) {
            logger.log(Level.WARNING, "Error terminating remote instance " + instanceName, ex);
        }
    }

    private List<ComputeEngineCloud> getClouds() {
        return Jenkins.get().clouds.stream()
                .filter(cloud -> cloud instanceof ComputeEngineCloud)
                .map(cloud -> (ComputeEngineCloud) cloud)
                .collect(Collectors.toList());
    }

    private Set<String> findLocalInstances(ComputeEngineCloud cloud) {
        return Jenkins.get().getNodes().stream()
                .filter(node -> node instanceof ComputeEngineInstance)
                .map(node -> (ComputeEngineInstance) node)
                .filter(node -> node.getCloud().equals(cloud))
                .map(Slave::getNodeName)
                .collect(Collectors.toSet());
    }

    private List<Instance> findRemoteInstances(ComputeEngineCloud cloud) {
        Map<String, String> filterLabel = ImmutableMap.of(CleanLostNodesWork.NODE_TYPE_LABEL_KEY, CleanLostNodesWork.NODE_TYPE_LABEL_VALUE);
        try {
            return cloud.getClient().listInstancesWithLabel(cloud.getProjectId(), filterLabel).stream()
                    .filter(instance -> shouldTerminateStatus(instance.getStatus()))
                    .collect(Collectors.toList());
        } catch (IOException ex) {
            logger.log(Level.WARNING, "Error finding remote instances", ex);
            return emptyList();
        }
    }

    private boolean shouldTerminateStatus(String status) {
        return !status.equals("STOPPING");
    }

    private void updateLocalInstancesLabel(Set<String> localInstances, ComputeEngineCloud cloud) {
        localInstances.forEach(instanceName -> {
            try {
                var instance = cloud.getClient().getInstance(cloud.getProjectId(), "", instanceName);
                instance.setLabels(ImmutableMap.of(NODE_IN_USE_LABEL_KEY, String.valueOf(System.currentTimeMillis())));
                logger.log(Level.FINE, "Updated label for instance " + instanceName);
            } catch (IOException ex) {
                logger.log(Level.WARNING, "Error updating label for instance " + instanceName, ex);
            }
        });
    }
}
