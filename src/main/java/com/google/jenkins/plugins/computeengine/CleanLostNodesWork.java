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
import com.google.common.collect.ImmutableMap;
import com.google.jenkins.plugins.computeengine.client.ComputeClientV2;
import hudson.Extension;
import hudson.model.PeriodicWork;
import hudson.model.Slave;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
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
    public static final long RECURRENCE_PERIOD = Long.parseLong(
            System.getProperty(CleanLostNodesWork.class.getName() + ".recurrencePeriod", String.valueOf(HOUR)));
    public static final int LOST_MULTIPLIER = 3;

    /** {@inheritDoc} */
    @Override
    public long getRecurrencePeriod() {
        return RECURRENCE_PERIOD;
    }

    /** {@inheritDoc} */
    @Override
    protected void doRun() {
        logger.log(Level.FINEST, "Starting clean lost nodes worker");
        getClouds().forEach(this::cleanCloud);
    }

    private void cleanCloud(ComputeEngineCloud cloud) {
        logger.log(Level.FINEST, "Cleaning cloud " + cloud.getCloudName());
        ComputeClientV2 clientV2 = ComputeClientV2.createFromComputeEngineCloud(cloud);
        List<Instance> remoteInstances = findRunningRemoteInstances(clientV2);
        Set<String> localInstances = findLocalInstances(cloud);
        if (!(localInstances.isEmpty() || remoteInstances.isEmpty())) {
            updateLocalInstancesLabel(clientV2, localInstances, remoteInstances);
        }
        remoteInstances.stream()
                .filter(remote -> isOrphaned(remote, localInstances))
                .forEach(remote -> terminateInstance(remote, cloud));
    }

    private boolean isOrphaned(Instance remote, Set<String> localInstances) {
        if (localInstances.contains(remote.getName())) {
            return false;
        }
        String nodeInUseTs = remote.getLabels().get(NODE_IN_USE_LABEL_KEY);
        if (nodeInUseTs == null) {
            return false;
        }
        long lastUsed = Long.parseLong(nodeInUseTs);
        var dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSSXXX");
        dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
        boolean isOrphan = lastUsed < System.currentTimeMillis() - RECURRENCE_PERIOD * LOST_MULTIPLIER;
        logger.log(
                Level.FINEST,
                "Instance " + remote.getName() + " last used at: " + dateFormat.format(lastUsed) + ", isOrphan: "
                        + isOrphan);
        return isOrphan;
    }

    private void terminateInstance(Instance remote, ComputeEngineCloud cloud) {
        String instanceName = remote.getName();
        logger.log(Level.INFO, "Removing orphaned instance: " + instanceName);
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
        var localInstances = Jenkins.get().getNodes().stream()
                .filter(node -> node instanceof ComputeEngineInstance)
                .map(node -> (ComputeEngineInstance) node)
                .filter(node -> node.getCloud().equals(cloud))
                .map(Slave::getNodeName)
                .collect(Collectors.toSet());
        logger.log(Level.FINEST, "Found " + localInstances.size() + " local instances");
        return localInstances;
    }

    private List<Instance> findRunningRemoteInstances(ComputeClientV2 clientV2) {
        try {
            var remoteInstances = clientV2.retrieveInstanceByLabelKeyAndStatus(NODE_IN_USE_LABEL_KEY, "RUNNING");
            logger.log(Level.FINEST, "Found " + remoteInstances.size() + " running remote instances");
            return remoteInstances;
        } catch (IOException ex) {
            logger.log(Level.WARNING, "Error finding remote instances", ex);
            return emptyList();
        }
    }

    /**
     * Updates the label of the local instances to indicate they are still in use. The method makes N network calls
     * for N local instances, couldn't find any bulk update apis.
     */
    private void updateLocalInstancesLabel(
            ComputeClientV2 clientV2, Set<String> localInstances, List<Instance> remoteInstances) {
        var remoteInstancesByName =
                remoteInstances.stream().collect(Collectors.toMap(Instance::getName, instance -> instance));
        var labelToUpdate = ImmutableMap.of(NODE_IN_USE_LABEL_KEY, String.valueOf(System.currentTimeMillis()));
        for (String instanceName : localInstances) {
            var remoteInstance = remoteInstancesByName.get(instanceName);
            if (remoteInstance == null) {
                continue;
            }
            try {
                clientV2.updateInstanceLabels(remoteInstance, labelToUpdate);
                logger.log(Level.FINEST, "Updated label for instance " + instanceName);
            } catch (IOException e) {
                logger.log(Level.WARNING, "Error updating label for instance " + instanceName, e);
            }
        }
    }
}
