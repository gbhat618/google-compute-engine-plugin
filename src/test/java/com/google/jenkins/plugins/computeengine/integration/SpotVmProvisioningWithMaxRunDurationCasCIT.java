/*
 * Copyright 2024 CloudBees, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.jenkins.plugins.computeengine.integration;

import static com.google.jenkins.plugins.computeengine.integration.ITUtil.LABEL;
import static com.google.jenkins.plugins.computeengine.integration.ITUtil.PROJECT_ID;
import static com.google.jenkins.plugins.computeengine.integration.ITUtil.ZONE;
import static com.google.jenkins.plugins.computeengine.integration.ITUtil.execute;
import static com.google.jenkins.plugins.computeengine.integration.ITUtil.getLabel;
import static com.google.jenkins.plugins.computeengine.integration.ITUtil.initClient;
import static com.google.jenkins.plugins.computeengine.integration.ITUtil.initCloud;
import static com.google.jenkins.plugins.computeengine.integration.ITUtil.initCredentials;
import static com.google.jenkins.plugins.computeengine.integration.ITUtil.windows;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeFalse;

import com.google.api.client.util.ArrayMap;
import com.google.api.services.compute.model.Instance;
import com.google.api.services.compute.model.Scheduling;
import com.google.cloud.graphite.platforms.plugin.client.ComputeClient;
import com.google.jenkins.plugins.computeengine.ComputeEngineCloud;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.model.labels.LabelAtom;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.tasks.Builder;
import io.jenkins.plugins.casc.ConfigurationAsCode;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import lombok.extern.java.Log;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.PrefixedOutputStream;
import org.jvnet.hudson.test.TailLog;
import org.jvnet.hudson.test.recipes.WithTimeout;

@Log
public class SpotVmProvisioningWithMaxRunDurationCasCIT {

    @ClassRule
    public static JenkinsRule j = new JenkinsRule();

    private static ComputeClient client;
    private static final Map<String, String> vmLabels = getLabel(SpotVmProvisioningWithMaxRunDurationCasCIT.class);

    @BeforeClass
    public static void init() throws Exception {
        assumeFalse(windows);
        log.info("init");
        initCredentials(j);
        initCloud(j);
        client = initClient(j, vmLabels, log);
        log.info("init complete");
    }

    /**
     * Ensures that an agent VM is provisioned for the first build (freestyle), and waits for the VM to be removed due to `maxRunDuration`.
     * Then schedules another build (pipeline), resulting in a new agent VM creation and success.
     * <p>
     * This test verifies that VM deletion on GCP does occur for `maxRunDuration` setting, and Jenkins agent is not
     * going to be in a wrong state, but gets deleted as well.
     * <p>
     * It covers both freestyle and pipeline builds.
     */
    @WithTimeout(600)
    @Test
    public void testMaxRunDurationDeletesAndNoNewBuilds() throws Exception {
        assumeFalse(windows);
        ConfigurationAsCode.get()
                .configure(Objects.requireNonNull(
                                this.getClass().getResource("spot-vm-provisioning-with-max-run-duration-casc.yml"))
                        .toString());

        // verify no nodes to start with.
        assertEquals(0, j.jenkins.getNodes().size());

        ComputeEngineCloud cloud = (ComputeEngineCloud) j.jenkins.clouds.getByName("gce-integration");
        cloud.getConfigurations().get(0).setGoogleLabels(vmLabels);
        Collection<PlannedNode> planned = cloud.provision(new LabelAtom(LABEL), 1);
        planned.iterator().next().future.get(); // wait for the node creation to finish
        Instance instance =
                client.getInstance(PROJECT_ID, ZONE, planned.iterator().next().displayName);
        assertEquals(1, j.jenkins.getNodes().size());

        // assert the scheduling configurations.
        Scheduling sch = instance.getScheduling();
        assertEquals("SPOT", sch.get("provisioningModel"));
        assertEquals("DELETE", sch.get("instanceTerminationAction"));
        assertEquals(180, Integer.parseInt((String) ((ArrayMap) sch.get("maxRunDuration")).get("seconds")));

        // try to execute build1 on the agent (freestyle type)
        FreeStyleProject fp = j.createFreeStyleProject("f");
        Builder step = execute(Commands.ECHO, "hello world");
        fp.getBuildersList().add(step);
        fp.setAssignedLabel(new LabelAtom(LABEL));
        try (var tailLog = new TailLog(j, "f", 1).withColor(PrefixedOutputStream.Color.CYAN)) {
            Future<FreeStyleBuild> buildFuture = fp.scheduleBuild2(0);
            FreeStyleBuild build = buildFuture.get();
            assertEquals(Result.SUCCESS, build.getResult());
            assertThat("Build didn't run on GCP", JenkinsRule.getLog(build), is(containsString(instance.getName())));
            tailLog.waitForCompletion();
        }

        // wait for the agent to be removed due to maxRunDuration; retention time is far more 30 minutes - so the
        // deletion should be due to maxRunDuration.
        await("Jenkins agent to be removed due to maxRunDuration well before the retention time")
                .atMost(180, TimeUnit.SECONDS)
                .until(() -> j.jenkins.getNodes().isEmpty());
        await("GCP VM deletion to complete due to maxRunDuration")
                .atMost(180, TimeUnit.SECONDS)
                .until(() -> client.listInstancesWithLabel(PROJECT_ID, vmLabels).isEmpty());

        // try to execute 2nd build, this time a pipeline type, and provisions a new agent.
        var p = j.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("node('" + LABEL + "') { sh 'date' }", true));
        try (var tailLog = new TailLog(j, "p", 1).withColor(PrefixedOutputStream.Color.MAGENTA)) {
            var run = j.buildAndAssertSuccess(p);
            assertEquals(
                    "jenkins is having more than agent", 1, j.jenkins.getNodes().size());
            String agentName = j.jenkins.getNodes().get(0).getNodeName();
            assertEquals(
                    "agent not present in GCP",
                    agentName,
                    client.getInstance(PROJECT_ID, ZONE, agentName).getName());
            assertThat("Build didn't run on GCP", JenkinsRule.getLog(run), is(containsString(agentName)));
            tailLog.waitForCompletion();
        }
        j.waitUntilNoActivity();
    }
}
