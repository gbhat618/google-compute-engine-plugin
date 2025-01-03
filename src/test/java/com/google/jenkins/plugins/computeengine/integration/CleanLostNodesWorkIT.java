package com.google.jenkins.plugins.computeengine.integration;

import static com.google.jenkins.plugins.computeengine.integration.ITUtil.LABEL;
import static com.google.jenkins.plugins.computeengine.integration.ITUtil.NULL_TEMPLATE;
import static com.google.jenkins.plugins.computeengine.integration.ITUtil.NUM_EXECUTORS;
import static com.google.jenkins.plugins.computeengine.integration.ITUtil.PROJECT_ID;
import static com.google.jenkins.plugins.computeengine.integration.ITUtil.ZONE;
import static com.google.jenkins.plugins.computeengine.integration.ITUtil.getLabel;
import static com.google.jenkins.plugins.computeengine.integration.ITUtil.initClient;
import static com.google.jenkins.plugins.computeengine.integration.ITUtil.instanceConfigurationBuilder;
import static org.awaitility.Awaitility.await;

import com.google.common.collect.ImmutableMap;
import com.google.jenkins.plugins.computeengine.CleanLostNodesWork;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

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

    @Rule
    public RealJenkinsRule rj1 = new RealJenkinsRule()
        .withColor(PrefixedOutputStream.Color.BLUE)
        .withLogger(CleanLostNodesWork.class, Level.FINEST);

    @Rule
    public RealJenkinsRule rj2 = new RealJenkinsRule()
        .javaOptions("-Djenkins.cloud.gcp.cleanLostNodesWork.recurrencePeriod=10000") // 10 seconds recurrence period
        .withColor(PrefixedOutputStream.Color.RED)
        .withLogger(CleanLostNodesWork.class, Level.FINEST);

    @Before
    public void init() throws Throwable {
        for (var rj : List.of(rj1, rj2)) {
            rj.runRemotely(ITUtil::initCredentials);
            rj.runRemotely(ITUtil::initCloud);
        }
    }

    @Test
    public void testCleanLostNode() throws Throwable {
        /* Trigger a build that takes sufficient time until
            * build has gone to running state on the remote VM,
            *   run the CleanLostNodesWork in the other VM --> question how to confirm it has run? maybe possible from the logs
            * crash the rj1.
            *   assert the VM is still there from the rj2.
            *   run the CleanLostNodesWork in the other VM --> now assert the logs that the VM is removed
            *                                                  also assert the VM is removed from the rj2
        * */
        rj1.runRemotely(CleanLostNodesWorkIT::startBuild);
    }

    static void startBuild(JenkinsRule r) throws IOException {
        try (var tail = new TailLog(r, "p1", 1).withColor(PrefixedOutputStream.Color.MAGENTA)) {
            var p1 = r.createProject(WorkflowJob.class, "p1");
            p1.setDefinition(new CpsFlowDefinition("node { echo 'hello'\nsleep 600\necho 'sleep done' }", true));
            p1.scheduleBuild2(0);
        }
    }
}
