/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.kubernetes.wrapper;

import io.kubernetes.client.openapi.models.AppsV1beta1Deployment;
import io.kubernetes.client.openapi.models.ExtensionsV1beta1Deployment;
import io.kubernetes.client.openapi.models.NetworkingV1beta1Ingress;
import io.kubernetes.client.openapi.models.V1ClusterRole;
import io.kubernetes.client.openapi.models.V1ClusterRoleBinding;
import io.kubernetes.client.openapi.models.V1ConfigMap;
import io.kubernetes.client.openapi.models.V1DaemonSet;
import io.kubernetes.client.openapi.models.V1Deployment;
import io.kubernetes.client.openapi.models.V1HorizontalPodAutoscaler;
import io.kubernetes.client.openapi.models.V1Job;
import io.kubernetes.client.openapi.models.V1Namespace;
import io.kubernetes.client.openapi.models.V1NetworkPolicy;
import io.kubernetes.client.openapi.models.V1PersistentVolume;
import io.kubernetes.client.openapi.models.V1PersistentVolumeClaim;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1ReplicaSet;
import io.kubernetes.client.openapi.models.V1ReplicationController;
import io.kubernetes.client.openapi.models.V1Role;
import io.kubernetes.client.openapi.models.V1RoleBinding;
import io.kubernetes.client.openapi.models.V1Secret;
import io.kubernetes.client.openapi.models.V1Service;
import io.kubernetes.client.openapi.models.V1ServiceAccount;
import io.kubernetes.client.openapi.models.V1StatefulSet;
import io.kubernetes.client.openapi.models.V1beta1CronJob;
import io.kubernetes.client.openapi.models.V1beta1DaemonSet;
import io.kubernetes.client.openapi.models.ExtensionsV1beta1Ingress;
import io.kubernetes.client.openapi.models.V1beta1ReplicaSet;
import io.kubernetes.client.openapi.models.V1beta1StatefulSet;
import io.kubernetes.client.openapi.models.V1beta2DaemonSet;
import io.kubernetes.client.openapi.models.V1beta2Deployment;
import io.kubernetes.client.openapi.models.V1beta2ReplicaSet;
import io.kubernetes.client.openapi.models.V1beta2StatefulSet;
import io.kubernetes.client.openapi.models.V2alpha1CronJob;
import io.kubernetes.client.openapi.models.V2beta1HorizontalPodAutoscaler;
import io.kubernetes.client.openapi.models.V2beta2HorizontalPodAutoscaler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sorts Kubernetes resource objects into a dependency-friendly processing order.
 *
 * <p>This class is a pure ordering helper: it only inspects the runtime class of the given
 * resource objects and never touches an {@code ApiClient}, performs IO or contacts a cluster.
 * Both entry points are side effect free: the input list is never modified and a new list is
 * returned.</p>
 *
 * <p>The apply ordering follows the dependency layers below. Resources inside the same layer
 * keep the relative order they had in the input (which mirrors the order of the documents in
 * the YAML files), so the result is a stable sort and does not drift with the iteration order
 * of any hash based collection:</p>
 * <ol>
 *     <li>{@link Layer#NAMESPACE} - a Namespace is the containment boundary of every
 *     namespaced resource and therefore has to exist first.</li>
 *     <li>{@link Layer#CLUSTER_SCOPE_FOUNDATION} - PersistentVolume, ClusterRole and
 *     ServiceAccount, which later resources may reference.</li>
 *     <li>{@link Layer#NAMESPACE_SCOPE_FOUNDATION} - Role, ConfigMap, Secret,
 *     PersistentVolumeClaim and NetworkPolicy, typically mounted or referenced by workloads.</li>
 *     <li>{@link Layer#ROLE_BINDING} - RoleBinding and ClusterRoleBinding. They are placed
 *     after the roles and service accounts they bind. Whether the API server rejects a binding
 *     that references a Role which does not exist yet depends on the RBAC privilege escalation
 *     check and the caller's permissions, so that rejection is not assumed; ordering the roles
 *     first simply makes the common case work without relying on the check.</li>
 *     <li>{@link Layer#WORKLOAD} - Service and all workload kinds (Deployment, StatefulSet,
 *     DaemonSet, ReplicaSet, ReplicationController, Job, CronJob and Pod) in every supported
 *     API version.</li>
 *     <li>{@link Layer#FRONTEND} - HorizontalPodAutoscaler and Ingress, which target the
 *     workloads and services created in the previous layer.</li>
 *     <li>{@link Layer#UNKNOWN} - every type not listed above.</li>
 * </ol>
 *
 * <p>Types absent from the table (for example custom resources and CustomResourceDefinitions,
 * which have no entry in {@link ResourceUpdaterMap}) are placed in the trailing
 * {@link Layer#UNKNOWN} layer, after every known layer. The reasoning: a built-in resource never
 * depends on an unknown one, while an unknown resource may depend on any of the built-ins, so
 * putting unknown resources last can only create missing dependencies in the safe direction; in
 * the current implementation such objects are skipped anyway, so the only observable effect of
 * the choice is where the "Skipped unsupported resource" messages appear.</p>
 *
 * <p>For deletion the layers are traversed in reverse so that dependents are removed before the
 * resources they depend on, which in particular makes the Namespace the very last deletion. The
 * relative order of resources within a layer stays stable; only the layer sequence is reversed.</p>
 */
public final class ResourceOrdering {

    /**
     * Dependency layers in apply order. The declaration order is the processing order.
     */
    enum Layer {
        /** Namespace resources, applied first and deleted last. */
        NAMESPACE,
        /** PersistentVolume, ClusterRole, ServiceAccount. */
        CLUSTER_SCOPE_FOUNDATION,
        /** Role, ConfigMap, Secret, PersistentVolumeClaim, NetworkPolicy. */
        NAMESPACE_SCOPE_FOUNDATION,
        /** RoleBinding, ClusterRoleBinding. */
        ROLE_BINDING,
        /** Service and all workload kinds. */
        WORKLOAD,
        /** HorizontalPodAutoscaler and Ingress. */
        FRONTEND,
        /** Resource kinds without an explicit ordering, applied last and deleted first. */
        UNKNOWN
    }

    /*
     * Exact-key class to layer lookup only. This map is never iterated to produce an order:
     * the processing order comes from Layer.values() (declaration order) and the stable
     * ArrayList buckets in sort(), so the iteration order of this HashMap cannot cause drift.
     */
    private static final Map<Class<?>, Layer> LAYERS;

    static {
        Map<Class<?>, Layer> layers = new HashMap<>();

        put(layers, Layer.NAMESPACE,
                V1Namespace.class);

        put(layers, Layer.CLUSTER_SCOPE_FOUNDATION,
                V1PersistentVolume.class,
                V1ClusterRole.class,
                V1ServiceAccount.class);

        put(layers, Layer.NAMESPACE_SCOPE_FOUNDATION,
                V1Role.class,
                V1ConfigMap.class,
                V1Secret.class,
                V1PersistentVolumeClaim.class,
                V1NetworkPolicy.class);

        put(layers, Layer.ROLE_BINDING,
                V1RoleBinding.class,
                V1ClusterRoleBinding.class);

        put(layers, Layer.WORKLOAD,
                V1Service.class,
                V1Deployment.class,
                V1StatefulSet.class,
                V1DaemonSet.class,
                V1ReplicaSet.class,
                V1ReplicationController.class,
                V1Job.class,
                V1Pod.class,
                ExtensionsV1beta1Deployment.class,
                AppsV1beta1Deployment.class,
                V1beta2Deployment.class,
                V1beta1DaemonSet.class,
                V1beta2DaemonSet.class,
                V1beta1ReplicaSet.class,
                V1beta2ReplicaSet.class,
                V1beta1StatefulSet.class,
                V1beta2StatefulSet.class,
                V1beta1CronJob.class,
                V2alpha1CronJob.class);

        put(layers, Layer.FRONTEND,
                V1HorizontalPodAutoscaler.class,
                V2beta1HorizontalPodAutoscaler.class,
                V2beta2HorizontalPodAutoscaler.class,
                ExtensionsV1beta1Ingress.class,
                NetworkingV1beta1Ingress.class);

        LAYERS = Collections.unmodifiableMap(layers);
    }

    private ResourceOrdering() {
    }

    /**
     * Stable sort of the resources for the apply (create / replace) flow.
     *
     * @param resources resources in their original, file based order
     * @param <T>       the resource type
     * @return a new list with the same elements grouped by dependency layer in apply order;
     *         the input list is not modified
     */
    public static <T> List<T> sortForApply(List<T> resources) {
        return sort(resources, false);
    }

    /**
     * Stable sort of the resources for the delete flow.
     *
     * <p>The dependency layers are traversed in reverse apply order so that the Namespace is
     * deleted last and resources depending on another resource are removed first. Elements
     * inside the same layer keep their original relative order.</p>
     *
     * @param resources resources in their original, file based order
     * @param <T>       the resource type
     * @return a new list with the same elements grouped by dependency layer in delete order;
     *         the input list is not modified
     */
    public static <T> List<T> sortForDelete(List<T> resources) {
        return sort(resources, true);
    }

    private static <T> List<T> sort(List<T> resources, boolean reverseLayers) {
        EnumMap<Layer, List<T>> buckets = new EnumMap<>(Layer.class);
        for (Layer layer : Layer.values()) {
            buckets.put(layer, new ArrayList<T>());
        }
        for (T resource : resources) {
            buckets.get(layerOf(resource)).add(resource);
        }

        List<T> sorted = new ArrayList<>(resources.size());
        Layer[] layers = Layer.values();
        for (int i = 0; i < layers.length; i++) {
            Layer layer = reverseLayers ? layers[layers.length - 1 - i] : layers[i];
            sorted.addAll(buckets.get(layer));
        }
        return sorted;
    }

    private static Layer layerOf(Object resource) {
        Layer layer = resource == null ? null : LAYERS.get(resource.getClass());
        return layer == null ? Layer.UNKNOWN : layer;
    }

    private static void put(Map<Class<?>, Layer> target, Layer layer, Class<?>... classes) {
        for (Class<?> clazz : classes) {
            target.put(clazz, layer);
        }
    }

    /**
     * @return an unmodifiable view of the class to layer mapping, used by tests to assert that
     *         every updater-supported kind has an explicit ordering.
     */
    static Map<Class<?>, Layer> getLayerMapping() {
        return LAYERS;
    }
}
