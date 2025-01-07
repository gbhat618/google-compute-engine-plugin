package com.google.jenkins.plugins.computeengine.integration;

import static com.google.jenkins.plugins.computeengine.integration.ITUtil.LABEL;
import static com.google.jenkins.plugins.computeengine.integration.ITUtil.NULL_TEMPLATE;
import static com.google.jenkins.plugins.computeengine.integration.ITUtil.NUM_EXECUTORS;
import static com.google.jenkins.plugins.computeengine.integration.ITUtil.PROJECT_ID;
import static com.google.jenkins.plugins.computeengine.integration.ITUtil.ZONE;
import static com.google.jenkins.plugins.computeengine.integration.ITUtil.getLabel;
import static com.google.jenkins.plugins.computeengine.integration.ITUtil.initCloud;
import static com.google.jenkins.plugins.computeengine.integration.ITUtil.initCredentials;
import static com.google.jenkins.plugins.computeengine.integration.ITUtil.instanceConfigurationBuilder;
import static com.google.jenkins.plugins.computeengine.integration.ITUtil.teardownResources;
import static org.awaitility.Awaitility.await;
import static org.junit.Assert.assertEquals;

import com.google.api.services.compute.model.Instance;
import com.google.jenkins.plugins.computeengine.ComputeEngineCloud;
import com.google.jenkins.plugins.computeengine.client.ComputeClientV2;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import lombok.extern.java.Log;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

@Log
public class ComputeClientV2IT {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private static final Map<String, String> googleLabels = getLabel(ComputeClientV2IT.class);
    private static final Map<String, String> nonJenkinsLabels = Map.of("non-jenkins-label", "non-jenkins-value");
    private static final Map<String, String> newLabels = Map.of("new-key", "new-value");

    @Before
    public void setUp() throws Exception {
        initCredentials(j);
        initCloud(j);
    }

    @After
    public void tearDown() throws IOException {
        teardownResources(getCloud(j).getClient(), googleLabels, log);
        teardownResources(getCloud(j).getClient(), nonJenkinsLabels, log);
        // merge googleLabels and newLabels for teardown purposes only
        Map<String, String> allLabels = new HashMap<>();
        allLabels.putAll(googleLabels);
        allLabels.putAll(newLabels);
        teardownResources(getCloud(j).getClient(), allLabels, log);
    }

    public ComputeEngineCloud getCloud(JenkinsRule j) {
        return (ComputeEngineCloud) j.jenkins.clouds.getByName("gce-integration");
    }

    // create dummy instances
    public String createOneInstance(ComputeClientV2 clientV2, Map<String, String> googleLabels) throws IOException {
        var instanceConfig = instanceConfigurationBuilder()
                .numExecutorsStr(NUM_EXECUTORS)
                .labels(LABEL)
                .oneShot(false)
                .createSnapshot(false)
                .template(NULL_TEMPLATE)
                .zone(ZONE)
                .googleLabels(googleLabels)
                .build();
        Instance instance = instanceConfig.instance();
        clientV2.getCompute().instances().insert(PROJECT_ID, ZONE, instance).execute();
        return instance.getName();
    }

    @Test
    public void testRetrieveInstancesByLabelAndStatus() throws Exception {
        ComputeClientV2 clientV2 = ComputeClientV2.createFromComputeEngineCloud(getCloud(j));
        String instance1 = createOneInstance(clientV2, googleLabels);
        String instance2 = createOneInstance(clientV2, nonJenkinsLabels);
        await().atMost(2, TimeUnit.MINUTES).until(() -> List.of(getInstance(clientV2, instance1), getInstance(clientV2, instance2)), Every.everyItem(instanceIsRunning()));
        String jenkinsKey = googleLabels.keySet().iterator().next();
        String nonJenkinsKey = nonJenkinsLabels.keySet().iterator().next();
        var matchingInstances = clientV2.retrieveInstanceByLabelKeyAndStatus(jenkinsKey, "RUNNING");
        assertEquals(1, matchingInstances.size());
        assertEquals(instance1, matchingInstances.get(0).getName());
        // stop the instance and see 0 matching instances for jenkins label in RUNNING state
        clientV2.getCompute().instances().stop(PROJECT_ID, ZONE, instance1).execute();
        await().timeout(5, TimeUnit.SECONDS)
                .until(() -> clientV2.retrieveInstanceByLabelKeyAndStatus(jenkinsKey, "RUNNING")
                        .isEmpty());
        await().timeout(30, TimeUnit.SECONDS)
                .until(() -> clientV2.retrieveInstanceByLabelKeyAndStatus(jenkinsKey, "STOPPING").stream()
                        .map(Instance::getName)
                        .collect(Collectors.toList())
                        .contains(instance1));
        // non-jenkins instance can also be filtered by label and status
        await().timeout(5, TimeUnit.SECONDS)
                .until(() -> clientV2.retrieveInstanceByLabelKeyAndStatus(nonJenkinsKey, "RUNNING").stream()
                        .map(Instance::getName)
                        .collect(Collectors.toList())
                        .contains(instance2));
    }

    @Test
    public void testUpdateInstanceLabels() throws Exception {
        ComputeClientV2 clientV2 = ComputeClientV2.createFromComputeEngineCloud(getCloud(j));
        String instance1 = createOneInstance(clientV2, googleLabels);
        await().atMost(2, TimeUnit.MINUTES).until(() -> Objects.nonNull(getInstance(clientV2, instance1)));
        clientV2.updateInstanceLabels(getInstance(clientV2, instance1), newLabels);
        assertLabel(clientV2, instance1, "new-key", "new-value");
        // change label value see if it gets updated
        clientV2.updateInstanceLabels(getInstance(clientV2, instance1), Map.of("new-key", "new-value-2"));
        assertLabel(clientV2, instance1, "new-key", "new-value-2");
        // change label value back so that instance can get teardown automatically
        clientV2.updateInstanceLabels(getInstance(clientV2, instance1), newLabels);
    }

    private static Instance getInstance(ComputeClientV2 clientV2, String instanceName) throws IOException {
        return clientV2.getCompute()
                .instances()
                .get(PROJECT_ID, ZONE, instanceName)
                .execute();
    }

    private static void assertLabel(ComputeClientV2 clientV2, String instanceName, String key, String value) {
        await().atMost(2, TimeUnit.MINUTES).until(() -> {
            var instance = clientV2.getCompute()
                    .instances()
                    .get(PROJECT_ID, ZONE, instanceName)
                    .execute();
            return instance.getLabels().containsKey(key)
                    && instance.getLabels().get(key).equals(value);
        });
    }
}
