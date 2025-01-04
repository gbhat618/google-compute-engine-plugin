package com.google.jenkins.plugins.computeengine.client;

import com.google.api.services.compute.Compute;
import com.google.api.services.compute.model.Instance;
import com.google.api.services.compute.model.InstancesScopedList;
import com.google.api.services.compute.model.InstancesSetLabelsRequest;
import com.google.cloud.graphite.platforms.plugin.client.ComputeClient;
import com.google.jenkins.plugins.computeengine.ComputeEngineCloud;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.Getter;

/**
 * Extends {@link com.google.cloud.graphite.platforms.plugin.client.ComputeClient} with additional functionalities.
 * <p>This class serves as a venue for implementing features not available in the archived Graphite Java library
 * (<a href="https://github.com/GoogleCloudPlatform/gcp-plugin-core-java">gcp-plugin-core-java</a>, last updated in December 2019).
 * Consideration for the gradual evolution of this class is suggested, including the re-implementation of methods
 * currently utilized from the Graphite library, to ensure dependency solely on the Google API Java Client Services
 * library (<a href="https://github.com/googleapis/google-api-java-client-services">google-api-java-client-services</a>).
 * This approach aims to eventually eliminate the reliance on the Graphite library.
 */
public class ComputeClientV2 {

    private final String projectId;

    @Getter
    private final Compute compute;

    public ComputeClientV2(String projectId, Compute compute) {
        this.projectId = projectId;
        this.compute = compute;
    }

    /**
     * Instantiates a {@link ComputeClientV2} using the configuration from a given {@link ComputeEngineCloud}.
     * <p>Jenkins may host multiple cloud configurations, each with distinct service accounts. This diversity necessitates leveraging an
     * existing {@link ComputeEngineCloud} instance to accurately configure {@link ComputeClientV2}. Interfacing directly with
     * {@link ComputeClient} is impractical due to its archived status and inability to extend, as noted in class-level documentation.
     * Consequently, this method employs reflection to retrieve the necessary {@link Compute} instance from within {@link ComputeClient}.
     *
     * @return A newly created {@link ComputeClientV2} instance, configured with the credentials and settings from the specified
     * {@link ComputeEngineCloud}.
     */
    public static ComputeClientV2 createFromComputeEngineCloud(ComputeEngineCloud cloud) {
        try {
            ComputeClient client = cloud.getClient();
            Field f = client.getClass().getDeclaredField("compute");
            f.setAccessible(true);
            Object wrapper = f.get(client);
            Field f2 = wrapper.getClass().getDeclaredField("compute");
            f2.setAccessible(true);
            ComputeClientV2 clientV2 = new ComputeClientV2(cloud.getProjectId(), (Compute) f2.get(wrapper));
            f.setAccessible(false);
            f2.setAccessible(false);
            return clientV2;
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Updates the labels of a specified {@code instance} by merging or replacing them with {@code newLabels}.
     * <p>This method adds any new labels found in {@code newLabels} to the instance's existing labels and updates
     * the values of any existing labels if they are also present in {@code newLabels}. Labels existing on the instance
     * that are not in {@code newLabels} remain unchanged. This operation can only result in the addition of new
     * labels or the modification of existing ones.
     *
     * @param instance  the instance whose labels are to be updated; must not be {@code null}
     * @param newLabels the new labels to be merged with or replace the existing labels of the instance; must not be {@code null}
     * @throws IOException if an I/O error occurs during the label update process.
     */
    public void updateInstanceLabels(Instance instance, Map<String, String> newLabels) throws IOException {
        var allLabels = instance.getLabels();
        allLabels.putAll(newLabels);
        var labelsRequest = new InstancesSetLabelsRequest()
                .setLabels(allLabels)
                .setLabelFingerprint(instance.getLabelFingerprint());
        String zoneLink = instance.getZone();
        String zone = zoneLink.substring(zoneLink.lastIndexOf("/") + 1);
        compute.instances()
                .setLabels(projectId, zone, instance.getName(), labelsRequest)
                .execute();
    }

    /**
     * Retrieves running instances filtered by a label key.
     * <p>Only instances labeled with the specified key and currently in RUNNING state are included.
     * Filtering follows the Google Compute Engine's aggregated list filtering syntax:
     * <a href="https://cloud.google.com/compute/docs/reference/rest/v1/instances/aggregatedList">
     * https://cloud.google.com/compute/docs/reference/rest/v1/instances/aggregatedList</a>.
     *
     * @param key the label key for filtering; should not be null or empty.
     * @return a list of {@link Instance} in RUNNING state with the specified label key, or empty if none found.
     * @throws IOException on communication errors with Compute Engine API.
     */
    public List<Instance> retrieveInstanceByLabelKeyAndStatus(String key, String status) throws IOException {
        String filter = "labels." + key + ":*" + " AND status=" + status;
        var response =
                compute.instances().aggregatedList(projectId).setFilter(filter).execute();
        var items = response.getItems();
        if (items == null) {
            return List.of();
        }
        return items.values().stream()
                .map(InstancesScopedList::getInstances)
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .collect(Collectors.toList());
    }
}
