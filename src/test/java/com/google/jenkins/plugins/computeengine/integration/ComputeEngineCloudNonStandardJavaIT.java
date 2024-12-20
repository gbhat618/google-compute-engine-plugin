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

package com.google.jenkins.plugins.computeengine.integration;

import static com.google.jenkins.plugins.computeengine.integration.ITUtil.BOOT_DISK_IMAGE_NAME;
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
import static com.google.jenkins.plugins.computeengine.integration.ITUtil.teardownResources;
import static com.google.jenkins.plugins.computeengine.integration.ITUtil.windows;
import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeFalse;

import com.google.api.services.compute.model.Instance;
import com.google.cloud.graphite.platforms.plugin.client.ComputeClient;
import com.google.common.collect.ImmutableList;
import com.google.jenkins.plugins.computeengine.ComputeEngineCloud;
import com.google.jenkins.plugins.computeengine.InstanceConfiguration;
import hudson.model.labels.LabelAtom;
import hudson.slaves.NodeProvisioner.PlannedNode;
import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.logging.Logger;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.recipes.WithTimeout;

/**
 * Integration test suite for {@link ComputeEngineCloudNonStandardJavaIT}. Verifies that instances
 * can be created using a non-standard Java executable path.
 */
public class ComputeEngineCloudNonStandardJavaIT {

    private static final String NON_STANDARD_JAVA_PATH = "/usr/bin/non-standard-java";

    private static Logger log = Logger.getLogger(ComputeEngineCloudNonStandardJavaIT.class.getName());

    @ClassRule
    public static JenkinsRule jenkinsRule = new JenkinsRule();

    private static ComputeClient client;
    private static Map<String, String> label = getLabel(ComputeEngineCloudNonStandardJavaIT.class);
    private static Collection<PlannedNode> planned;
    private static Instance instance;

    @BeforeClass
    public static void init() throws Exception {
        assumeFalse(windows);
        log.info("init");
        initCredentials(jenkinsRule);
        ComputeEngineCloud cloud = initCloud(jenkinsRule);
        client = initClient(jenkinsRule, label, log);
        InstanceConfiguration instanceConfiguration = instanceConfigurationBuilder()
                .bootDiskSourceImageName(BOOT_DISK_IMAGE_NAME + "-non-standard-java")
                .numExecutorsStr(NUM_EXECUTORS)
                .labels(LABEL)
                .oneShot(false)
                .createSnapshot(false)
                .template(NULL_TEMPLATE)
                .javaExecPath(NON_STANDARD_JAVA_PATH)
                .googleLabels(label)
                .build();

        cloud.setConfigurations(ImmutableList.of(instanceConfiguration));
        planned = cloud.provision(new LabelAtom(LABEL), 1);
        planned.iterator().next().future.get();
        instance = cloud.getClient()
                .getInstance(PROJECT_ID, ZONE, planned.iterator().next().displayName);
    }

    @AfterClass
    public static void teardown() throws IOException {
        teardownResources(client, label, log);
    }

    @WithTimeout(300)
    @Test
    public void testWorkerCreatedOnePlannedNode() {
        assertEquals(1, planned.size());
    }

    @WithTimeout(300)
    @Test
    public void testInstanceStatusRunning() {
        assertEquals("RUNNING", instance.getStatus());
    }
}
