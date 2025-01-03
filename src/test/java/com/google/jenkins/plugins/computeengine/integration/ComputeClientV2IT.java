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
import static org.awaitility.Awaitility.await;
import static org.junit.Assert.assertEquals;

import com.google.api.services.compute.model.Instance;
import com.google.common.collect.ImmutableMap;
import com.google.jenkins.plugins.computeengine.ComputeEngineCloud;
import com.google.jenkins.plugins.computeengine.client.ComputeClientV2;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

public class ComputeClientV2IT {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    public void init(JenkinsRule j) throws Exception {
        initCredentials(j);
        initCloud(j);
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
    public void testLabelFiltering() throws Exception {
        init(j);
        ComputeClientV2 clientV2 = ComputeClientV2.createFromComputeEngineCloud(getCloud(j));
        var googleLabels = getLabel(ComputeClientV2IT.class);
        String instance1 = createOneInstance(clientV2, googleLabels);
        String instance2 = createOneInstance(clientV2, ImmutableMap.of("non-jenkins-label", "non-jenkins-value"));
        await().atMost(2, TimeUnit.MINUTES).until(() -> {
            var i1 = clientV2.getCompute()
                    .instances()
                    .get(PROJECT_ID, ZONE, instance1)
                    .execute();
            var i2 = clientV2.getCompute()
                    .instances()
                    .get(PROJECT_ID, ZONE, instance2)
                    .execute();
            return i1 != null && i2 != null && i1.getStatus().equals("RUNNING") && i2.getStatus().equals("RUNNING");
        });
        String key = googleLabels.keySet().iterator().next();
        var matchingInstances = clientV2.retrieveRunningInstanceByLabelKey(key);
        assertEquals(1, matchingInstances.size());
        // stop the instance and notice 0 instance retrieved
        clientV2.getCompute()
                .instances()
                .stop(PROJECT_ID, ZONE, matchingInstances.get(0).getName())
                .execute();
        await().timeout(5, TimeUnit.SECONDS)
                .until(() -> clientV2.retrieveRunningInstanceByLabelKey(key).isEmpty());

        // clean up
        clientV2.getCompute().instances().delete(PROJECT_ID, ZONE, instance1).execute();
        clientV2.getCompute().instances().delete(PROJECT_ID, ZONE, instance2).execute();
    }

    @Test
    public void updateLabels() throws Exception {
        init(j);
        ComputeClientV2 clientV2 = ComputeClientV2.createFromComputeEngineCloud(getCloud(j));
        var googleLabels = getLabel(ComputeClientV2IT.class);
        String instance1 = createOneInstance(clientV2, googleLabels);
        await().atMost(2, TimeUnit.MINUTES)
                .until(() -> Objects.nonNull(clientV2.getCompute()
                        .instances()
                        .get(PROJECT_ID, ZONE, instance1)
                        .execute()));
        Instance remote = clientV2.getCompute()
                .instances()
                .get(PROJECT_ID, ZONE, instance1)
                .execute();
        var newLabels = ImmutableMap.of("new-key", "new-value");
        clientV2.updateInstanceLabels(remote, newLabels);
        await().atMost(2, TimeUnit.MINUTES).until(() -> {
            var updatedInstance = clientV2.getCompute()
                    .instances()
                    .get(PROJECT_ID, ZONE, instance1)
                    .execute();
            return (updatedInstance.getLabels().containsKey("new-key")
                    && updatedInstance.getLabels().get("new-key").equals("new-value"));
        });

        // clean up
        clientV2.getCompute().instances().delete(PROJECT_ID, ZONE, instance1).execute();
    }
}
