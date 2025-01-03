package com.google.jenkins.plugins.computeengine.integration;

import static com.google.jenkins.plugins.computeengine.integration.ITUtil.LABEL;
import static com.google.jenkins.plugins.computeengine.integration.ITUtil.NULL_TEMPLATE;
import static com.google.jenkins.plugins.computeengine.integration.ITUtil.NUM_EXECUTORS;
import static com.google.jenkins.plugins.computeengine.integration.ITUtil.getLabel;
import static com.google.jenkins.plugins.computeengine.integration.ITUtil.initCloud;
import static com.google.jenkins.plugins.computeengine.integration.ITUtil.initCredentials;
import static com.google.jenkins.plugins.computeengine.integration.ITUtil.instanceConfigurationBuilder;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertNotEquals;

import com.google.common.collect.ImmutableList;
import com.google.jenkins.plugins.computeengine.CleanLostNodesWork;
import com.google.jenkins.plugins.computeengine.ComputeEngineCloud;
import hudson.logging.LogRecorder;
import hudson.logging.LogRecorderManager;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
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

    @Rule
    public RealJenkinsRule rj1 = new RealJenkinsRule()
            .javaOptions("-Djenkins.cloud.gcp.cleanLostNodesWork.recurrencePeriod="
                    + CLEAN_LOST_NODES_WORK_RECURRENCE_PERIOD)
            .withColor(PrefixedOutputStream.Color.BLUE)
            .withLogger(CleanLostNodesWork.class, Level.FINEST);

    @Rule
    public RealJenkinsRule rj2 = new RealJenkinsRule()
            .javaOptions("-Djenkins.cloud.gcp.cleanLostNodesWork.recurrencePeriod="
                    + CLEAN_LOST_NODES_WORK_RECURRENCE_PERIOD)
            .withColor(PrefixedOutputStream.Color.RED)
            .withLogger(CleanLostNodesWork.class, Level.FINEST);

    private static final Map<String, String> googleLabels = getLabel(CleanLostNodesWorkIT.class);

    @Before
    public void init() throws Throwable {
        for (var rj : List.of(rj1, rj2)) {
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

                // init log recorder
                LogRecorderManager lrm = r.jenkins.getLog();
                LogRecorder lr = new LogRecorder(LOG_RECORDER_NAME);
                LogRecorder.Target target = new LogRecorder.Target(CleanLostNodesWork.class.getName(), Level.FINEST);
                lr.setLoggers(List.of(target));
                lrm.getRecorders().add(lr);
            });
        }
    }

    @Test
    public void doNotDeleteInUseNodeInOtherController() throws Throwable {
        rj1.runRemotely(j -> {
            var p1 = createPipeline(j);
            try (var tail = new TailLog(j, "p1", 1).withColor(PrefixedOutputStream.Color.MAGENTA)) {
                j.buildAndAssertSuccess(p1);
                tail.waitForCompletion();

                // assert logs
                var logRecords =
                        Jenkins.get().getLog().getLogRecorder(LOG_RECORDER_NAME).getLogRecords();
                assertLogContainsMessage(logRecords, "Found 1 remote instances");
                assertLogContainsMessage(logRecords, "Found 1 local instances");
                assertLogContainsMessage(logRecords, "Updated label for instance");
                assertLogDoesNotContainsMessage(logRecords, "toRemove: true");
                assertLogDoesNotContainsMessage(logRecords, "not found locally, removing it");
            }
        });
        rj2.runRemotely(j -> {
            // assert logs
            var logRecords =
                    Jenkins.get().getLog().getLogRecorder(LOG_RECORDER_NAME).getLogRecords();
            assertLogContainsMessage(logRecords, "Found 1 remote instances");
            assertLogContainsMessage(logRecords, "Found 0 local instances");
            assertLogDoesNotContainsMessage(logRecords, "Found 1 local instances");
            assertLogDoesNotContainsMessage(logRecords, "Updated label for instance");
            assertLogDoesNotContainsMessage(logRecords, "toRemove: true");
        });
    }

    @Test
    public void cleanLostNode() throws Throwable {
        rj1.runRemotely(j -> {
            var p1 = createPipeline(j);
            try (var tail = new TailLog(j, "p1", 1).withColor(PrefixedOutputStream.Color.MAGENTA)) {
                var run = p1.scheduleBuild2(0).waitForStart();
                await().timeout(4, TimeUnit.MINUTES).until(() -> run.getLog().contains("Running on"));
                log.info("Build is already running, can proceed to stopping jenkins to make the agent a lost VM");

                // assert that the VMs label was updated
                var logRecords =
                        Jenkins.get().getLog().getLogRecorder(LOG_RECORDER_NAME).getLogRecords();
                assertLogContainsMessage(logRecords, "Found 1 remote instances");
                assertLogContainsMessage(logRecords, "Found 1 local instances");
                assertLogContainsMessage(logRecords, "Updated label for instance");
                assertLogDoesNotContainsMessage(logRecords, "toRemove: true");
                assertLogDoesNotContainsMessage(logRecords, "not found locally, removing it");
            }
        });
        rj1.stopJenkins();

        rj2.runRemotely(j -> {
            var cloud = (ComputeEngineCloud) j.jenkins.clouds.getByName("gce-integration");
            assertThat(
                    "VM is still there",
                    cloud.getClient()
                                    .listInstancesWithLabel(cloud.getProjectId(), googleLabels)
                                    .size()
                            == 1);
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

            // assert logs
            var logRecords =
                    Jenkins.get().getLog().getLogRecorder(LOG_RECORDER_NAME).getLogRecords();
            assertLogContainsMessage(logRecords, "Found 1 remote instances");
            assertLogContainsMessage(logRecords, "Found 0 local instances");
            assertLogContainsMessage(logRecords, "toRemove: true");
            assertLogContainsMessage(logRecords, "not found locally, removing it");
            assertLogDoesNotContainsMessage(logRecords, "Found 1 local instances");
            assertLogDoesNotContainsMessage(logRecords, "Updated label for instance");
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

    private static void assertLogContainsMessage(List<LogRecord> logRecords, String text) {
        assertThat(logRecords, hasItem(hasProperty("message", containsString(text))));
    }

    private static void assertLogDoesNotContainsMessage(List<LogRecord> logRecords, String text) {
        assertThat(logRecords, not(hasItem(hasProperty("message", containsString(text)))));
    }
}
