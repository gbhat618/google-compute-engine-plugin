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
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
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
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import lombok.extern.java.Log;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.Timeout;
import org.jvnet.hudson.test.JenkinsRule;

@Log
public class MaxRunDurationCasCIT {

    @ClassRule
    public static Timeout timeout = new Timeout(20, TimeUnit.MINUTES);

    @ClassRule
    public static JenkinsRule j = new JenkinsRule();

    private static ComputeClient client;
    private static ComputeEngineCloud cloud;
    private static Map<String, String> label = getLabel(MaxRunDurationCasCIT.class);

    @BeforeClass
    public static void init() throws Exception {
        assumeFalse(windows);
        log.info("init");
        initCredentials(j);
        cloud = initCloud(j);
        client = initClient(j, label, log);
    }

    @Test
    public void testMaxRunDurationDeletesAndNoNewBuilds() throws Exception {
        assumeFalse(windows);
        ConfigurationAsCode.get()
                .configure(Objects.requireNonNull(this.getClass().getResource("casc-max-run-duration-agent-it.yml"))
                        .toString());
        ComputeEngineCloud cloud = (ComputeEngineCloud) j.jenkins.clouds.getByName("gce-integration");
        cloud.getConfigurations().get(0).setGoogleLabels(label);
        Collection<PlannedNode> planned = cloud.provision(new LabelAtom(LABEL), 1);
        planned.iterator().next().future.get(); // wait for the node creation to finish
        Instance instance =
                client.getInstance(PROJECT_ID, ZONE, planned.iterator().next().displayName);
        String instanceName = instance.getName();
        log.info("Instance: " + instance.getName());

        // assert the scheduling configurations.
        Scheduling sch = instance.getScheduling();
        assertEquals("SPOT", sch.get("provisioningModel"));
        assertEquals("DELETE", sch.get("instanceTerminationAction"));
        assertEquals(180, Integer.parseInt((String) ((ArrayMap) sch.get("maxRunDuration")).get("seconds")));
        log.info("instance scheduling configs are correct");

        // try to execute a build on the agent
        FreeStyleProject fp = j.createFreeStyleProject();
        Builder step = execute(Commands.ECHO, "hello world");
        fp.getBuildersList().add(step);
        fp.setAssignedLabel(new LabelAtom(LABEL));
        Future<FreeStyleBuild> buildFuture = fp.scheduleBuild2(0);
        FreeStyleBuild build = buildFuture.get();
        assertEquals(Result.SUCCESS, build.getResult());
        String agent1 = printLogsAndReturnAgentName(build);
        log.info("first build completed");
        assertEquals(agent1, instanceName);

        // wait for 3 minutes to make sure the instance is fully deleted due to `maxRunDuration`
        log.info("sleeping 180s to make sure the instance is deleted");
        TimeUnit.SECONDS.sleep(180);
        log.info("sleeping completed");

        // assert there are no nodes remaining;
        assertTrue(client.listInstancesWithLabel(PROJECT_ID, label).isEmpty());

        // trigger another build, notice a new instance is being created
        log.info("proceeding to 2nd build, after no remaining instances");
        buildFuture = fp.scheduleBuild2(0);
        build = buildFuture.get();
        String agent2 = printLogsAndReturnAgentName(build);
        log.info("second build completed");

        assertNotEquals(agent1, agent2);
    }

    private static String printLogsAndReturnAgentName(FreeStyleBuild build) throws IOException {
        List<String> logs = build.getLog(50);
        String agentName = null;
        for (String line : logs) {
            if (line.contains("Building remotely on")) {
                agentName = line.split(" ")[3];
            }
            log.info(line);
        }
        return agentName;
    }
}
