package com.google.jenkins.plugins.computeengine.integration;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.not;

import com.google.jenkins.plugins.computeengine.CleanLostNodesWork;
import hudson.logging.LogRecorder;
import hudson.logging.LogRecorderManager;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.jvnet.hudson.test.JenkinsRule;

/**
 * Utility class for asserting log messages in Jenkins when using {@code RealJenkinsRule}.
 * If the test is only using {@code JenkinsRule}, then use the {@code LoggerRule} instead.
 * Note: Probably it is better to have these kind of utilities in the jenkins-test-harness itself instead, but at least as of now, such utilities are not available.
 */
public class RealJenkinsLogUtil {

    public static void setupLogRecorder(JenkinsRule j, String logRecorderName) {
        LogRecorderManager lrm = j.jenkins.getLog();
        LogRecorder lr = new LogRecorder(logRecorderName);
        LogRecorder.Target target = new LogRecorder.Target(CleanLostNodesWork.class.getName(), Level.FINEST);
        lr.setLoggers(List.of(target));
        lrm.getRecorders().add(lr);
    }

    private static List<LogRecord> getLogRecords(JenkinsRule j, String logRecorderName) {
        return j.jenkins.getLog().getLogRecorder(logRecorderName).getLogRecords();
    }

    public static void assertLogContains(JenkinsRule j, String logRecorder, String... texts) {
        var logRecords = getLogRecords(j, logRecorder);
        for (var text : texts) {
            assertThat(logRecords, hasItem(hasProperty("message", containsString(text))));
        }
    }

    public static void assertLogDoesNotContain(JenkinsRule j, String logRecorder, String... texts) {
        var logRecords = getLogRecords(j, logRecorder);
        for (var text : texts) {
            assertThat(logRecords, not(hasItem(hasProperty("message", containsString(text)))));
        }
    }
}
