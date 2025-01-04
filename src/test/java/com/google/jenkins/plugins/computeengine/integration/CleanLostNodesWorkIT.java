package com.google.jenkins.plugins.computeengine.integration;

import static com.google.jenkins.plugins.computeengine.integration.ITUtil.LABEL;
import static com.google.jenkins.plugins.computeengine.integration.ITUtil.NULL_TEMPLATE;
import static com.google.jenkins.plugins.computeengine.integration.ITUtil.NUM_EXECUTORS;
import static com.google.jenkins.plugins.computeengine.integration.ITUtil.getLabel;
import static com.google.jenkins.plugins.computeengine.integration.ITUtil.initCloud;
import static com.google.jenkins.plugins.computeengine.integration.ITUtil.initCredentials;
import static com.google.jenkins.plugins.computeengine.integration.ITUtil.instanceConfigurationBuilder;
import static com.google.jenkins.plugins.computeengine.integration.ITUtil.teardownResources;
import static org.awaitility.Awaitility.await;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import com.google.common.collect.ImmutableList;
import com.google.jenkins.plugins.computeengine.CleanLostNodesWork;
import com.google.jenkins.plugins.computeengine.ComputeEngineCloud;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.PrefixedOutputStream;
import org.jvnet.hudson.test.RealJenkinsRule;
import org.jvnet.hudson.test.TailLog;

public class CleanLostNodesWorkIT {
    private static final Logger log = Logger.getLogger(CleanLostNodesWorkIT.class.getName());
    private static final String LOG_RECORDER_NAME = "CleanLostNodesWork log recorder";
    /* The GCP VM provisioning takes a while, the first time label value is put when the VM is provision request is
        sent. So the timestamp can be quite old, by the time the VM is discovered by the CleanLostNodesWork to update the
        label again. I tried with 30s, and 45s, didn't work. but 60s works correctly (as of now at least).
    */
    private static final long CLEAN_LOST_NODES_WORK_RECURRENCE_PERIOD = 60 * 1000;
    private static final Map<String, String> googleLabels = getLabel(CleanLostNodesWorkIT.class);

    @Rule
    public RealJenkinsRule rj1 = new RealJenkinsRule();

    @Rule
    public RealJenkinsRule rj2 = new RealJenkinsRule();

    @Before
    public void init() throws Throwable {
        Stack<PrefixedOutputStream.Color> colors = new Stack<>() {
            {
                push(PrefixedOutputStream.Color.BLUE);
                push(PrefixedOutputStream.Color.RED);
            }
        };
        for (var rj : List.of(rj1, rj2)) {
            rj.javaOptions("-Djenkins.cloud.gcp.cleanLostNodesWork.recurrencePeriod="
                            + CLEAN_LOST_NODES_WORK_RECURRENCE_PERIOD)
                    .withLogger(CleanLostNodesWork.class, Level.FINEST)
                    .withColor(colors.pop());
            rj.startJenkins();
            rj.runRemotely(r -> {
                initCredentials(r);
                var cloud = initCloud(r);
                var instanceConfig = instanceConfigurationBuilder()
                        .numExecutorsStr(NUM_EXECUTORS)
                        .labels(LABEL)
                        .oneShot(false)
                        .createSnapshot(false)
                        .template(NULL_TEMPLATE)
                        .googleLabels(googleLabels)
                        .cloud(cloud)
                        .build();
                cloud.setConfigurations(ImmutableList.of(instanceConfig));
                RealJenkinsLogUtil.setupLogRecorder(r, LOG_RECORDER_NAME);
            });
        }
    }

    @After
    public void tearDown() throws Throwable {
        rj2.runRemotely(j -> {
            var cloud = (ComputeEngineCloud) j.jenkins.clouds.getByName("gce-integration");
            teardownResources(cloud.getClient(), googleLabels, log);
        });
    }

    @Test
    public void testNodeInUseWontDeleteByOtherController() throws Throwable {
        rj1.runRemotely(j -> {
            var p1 = createPipeline(j);
            try (var tail = new TailLog(j, "p1", 1).withColor(PrefixedOutputStream.Color.MAGENTA)) {
                j.buildAndAssertSuccess(p1);
                tail.waitForCompletion();
                RealJenkinsLogUtil.assertLogContains(
                        j,
                        LOG_RECORDER_NAME,
                        "Found 1 running remote instances",
                        "Found 1 local instances",
                        "Updated label for instance");
                RealJenkinsLogUtil.assertLogDoesNotContain(
                        j, LOG_RECORDER_NAME, "isOrphan: true", "not found locally, removing it");
            }
        });
        rj2.runRemotely(j -> {
            RealJenkinsLogUtil.assertLogContains(
                    j, LOG_RECORDER_NAME, "Found 1 running remote instances", "Found 0 local instances");
            RealJenkinsLogUtil.assertLogDoesNotContain(
                    j,
                    LOG_RECORDER_NAME,
                    "Found 1 local instances",
                    "Updated label for instance",
                    "isOrphan: true",
                    "not found locally, removing it");
        });
    }

    @Test
    public void testLostNodeCleanedUpBySecondController() throws Throwable {
        rj1.runRemotely(j -> {
            var p1 = createPipeline(j);
            try (var tail = new TailLog(j, "p1", 1).withColor(PrefixedOutputStream.Color.MAGENTA)) {
                var run = p1.scheduleBuild2(0).waitForStart();
                await().timeout(4, TimeUnit.MINUTES).until(() -> run.getLog().contains("Running on"));
                log.info("Build is already running, can proceed to stopping jenkins to make the agent a lost VM");
                RealJenkinsLogUtil.assertLogContains(
                        j,
                        LOG_RECORDER_NAME,
                        "Found 1 running remote instances",
                        "Found 1 local instances",
                        "Updated label for instance");
                RealJenkinsLogUtil.assertLogDoesNotContain(
                        j, LOG_RECORDER_NAME, "isOrphan: true", "not found locally, removing it");
            }
        });
        rj1.stopJenkins();

        rj2.runRemotely(j -> {
            var cloud = (ComputeEngineCloud) j.jenkins.clouds.getByName("gce-integration");
            assertEquals(
                    "VM is still there",
                    1,
                    cloud.getClient()
                            .listInstancesWithLabel(cloud.getProjectId(), googleLabels)
                            .size());
            TimeUnit.SECONDS.sleep(getSleepSeconds());

            var instances = cloud.getClient().listInstancesWithLabel(cloud.getProjectId(), googleLabels);
            if (!instances.isEmpty()) {
                assertNotEquals(
                        "VM is not running",
                        "RUNNING",
                        cloud.getClient()
                                .listInstancesWithLabel(cloud.getProjectId(), googleLabels)
                                .get(0)
                                .getStatus());
            }
            await("VM is not there").timeout(4, TimeUnit.MINUTES).until(() -> cloud.getClient()
                    .listInstancesWithLabel(cloud.getProjectId(), googleLabels)
                    .isEmpty());
            RealJenkinsLogUtil.assertLogContains(
                    j,
                    LOG_RECORDER_NAME,
                    "Found 1 running remote instances",
                    "Found 0 local instances",
                    "isOrphan: true",
                    "not found locally, removing it");
            RealJenkinsLogUtil.assertLogDoesNotContain(
                    j, LOG_RECORDER_NAME, "Found 1 local instances", "Updated label for instance");
        });
    }

    private static WorkflowJob createPipeline(JenkinsRule j) throws IOException {
        var p1 = j.createProject(WorkflowJob.class, "p1");
        p1.setDefinition(new CpsFlowDefinition(
                "node('integration') { echo 'hello'\nsleep " + getSleepSeconds() + "\necho 'sleep " + "done' }", true));
        return p1;
    }

    private static long getSleepSeconds() {
        return CLEAN_LOST_NODES_WORK_RECURRENCE_PERIOD * (CleanLostNodesWork.LOST_MULTIPLIER + 1) / 1000;
    }
}
