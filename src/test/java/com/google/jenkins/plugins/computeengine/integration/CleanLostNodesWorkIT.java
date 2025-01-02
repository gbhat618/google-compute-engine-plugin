package com.google.jenkins.plugins.computeengine.integration;

import static com.google.jenkins.plugins.computeengine.integration.ITUtil.LABEL;
import static com.google.jenkins.plugins.computeengine.integration.ITUtil.NULL_TEMPLATE;
import static com.google.jenkins.plugins.computeengine.integration.ITUtil.NUM_EXECUTORS;
import static com.google.jenkins.plugins.computeengine.integration.ITUtil.PROJECT_ID;
import static com.google.jenkins.plugins.computeengine.integration.ITUtil.ZONE;
import static com.google.jenkins.plugins.computeengine.integration.ITUtil.getLabel;
import static com.google.jenkins.plugins.computeengine.integration.ITUtil.initClient;
import static com.google.jenkins.plugins.computeengine.integration.ITUtil.initCloud;
import static com.google.jenkins.plugins.computeengine.integration.ITUtil.initCredentials;
import static com.google.jenkins.plugins.computeengine.integration.ITUtil.instanceConfigurationBuilder;
import static org.awaitility.Awaitility.await;

import com.google.common.collect.ImmutableMap;
import com.google.jenkins.plugins.computeengine.CleanLostNodesWork;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

public class CleanLostNodesWorkIT {
    private static final Logger log = Logger.getLogger(CleanLostNodesWorkIT.class.getName());

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Before
    public void setUp() {
        Logger.getLogger("com.google.jenkins.plugins.computeengine.CleanLostNodesWork").setLevel(Level.FINE);
    }

    void init() throws Exception {
        initCredentials(j);
        initCloud(j);
    }

    @Test
    public void testWorkerCreatedWithoutNoDelayProvision() throws Exception {
        init();
        var client = initClient(j, getLabel(CleanLostNodesWorkIT.class), log);

        var instanceConfig = instanceConfigurationBuilder()
            .createSnapshot(false)
            .numExecutorsStr(NUM_EXECUTORS)
            .labels(LABEL)
            .oneShot(false)
            .template(NULL_TEMPLATE)
            .googleLabels(ImmutableMap.of(
                CleanLostNodesWork.NODE_IN_USE_LABEL_KEY,
                String.valueOf(System.currentTimeMillis() - 4 * CleanLostNodesWork.RECURRENCE_PERIOD),
                CleanLostNodesWork.NODE_TYPE_LABEL_KEY, CleanLostNodesWork.NODE_TYPE_LABEL_VALUE
            ))
            .build();
        var instance = instanceConfig.instance();
        String name = "orphan-instance-2";
        instance.setName(name);

        client.insertInstance(PROJECT_ID, Optional.empty(), instance);
        await("orphan instance inserted and running")
                .timeout(2, TimeUnit.MINUTES)
                .until(() -> client.getInstance(PROJECT_ID, ZONE, name)
                        .getStatus()
                        .equals("RUNNING"));
        new CleanLostNodesWork().doRun();
        await("orphan instance deleted")
                .timeout(2, TimeUnit.MINUTES)
                .until(() -> !client.getInstance(PROJECT_ID, ZONE, name)
                                   .getStatus()
                                   .equals("RUNNING"));
    }
}
