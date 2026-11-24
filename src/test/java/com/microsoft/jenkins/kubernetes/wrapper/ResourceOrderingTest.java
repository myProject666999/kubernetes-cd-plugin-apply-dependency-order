/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.kubernetes.wrapper;

import io.kubernetes.client.openapi.models.ExtensionsV1beta1Deployment;
import io.kubernetes.client.openapi.models.ExtensionsV1beta1Ingress;
import io.kubernetes.client.openapi.models.V1ClusterRole;
import io.kubernetes.client.openapi.models.V1ClusterRoleBinding;
import io.kubernetes.client.openapi.models.V1ConfigMap;
import io.kubernetes.client.openapi.models.V1Deployment;
import io.kubernetes.client.openapi.models.V1HorizontalPodAutoscaler;
import io.kubernetes.client.openapi.models.V1Namespace;
import io.kubernetes.client.openapi.models.V1PersistentVolume;
import io.kubernetes.client.openapi.models.V1PersistentVolumeClaim;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1Role;
import io.kubernetes.client.openapi.models.V1RoleBinding;
import io.kubernetes.client.openapi.models.V1Secret;
import io.kubernetes.client.openapi.models.V1Service;
import io.kubernetes.client.openapi.models.V1ServiceAccount;
import io.kubernetes.client.openapi.models.V1StatefulSet;
import io.kubernetes.client.openapi.models.V2beta2HorizontalPodAutoscaler;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link ResourceOrdering}. Everything is built from in-memory model objects, no
 * {@code ApiClient} or cluster connection is involved.
 */
public class ResourceOrderingTest {

    private static List<Object> shuffledSample() {
        // Deliberately shuffled across all seven layers and with two Secrets in a given order.
        return new ArrayList<>(Arrays.asList(
                new V1HorizontalPodAutoscaler(),                 // FRONTEND
                new V1Secret().metadata(meta("secret-a")),      // NAMESPACE_SCOPE_FOUNDATION
                new V1Deployment(),                              // WORKLOAD
                new V1Namespace(),                               // NAMESPACE
                new V1RoleBinding(),                             // ROLE_BINDING
                new V1Secret().metadata(meta("secret-b")),      // NAMESPACE_SCOPE_FOUNDATION
                new V1ClusterRoleBinding(),                      // ROLE_BINDING
                new V1PersistentVolumeClaim(),                   // NAMESPACE_SCOPE_FOUNDATION
                new V1Pod(),                                     // WORKLOAD
                new V1ClusterRole(),                             // CLUSTER_SCOPE_FOUNDATION
                new V2beta2HorizontalPodAutoscaler(),            // FRONTEND
                new V1PersistentVolume(),                        // CLUSTER_SCOPE_FOUNDATION
                new V1ConfigMap(),                               // NAMESPACE_SCOPE_FOUNDATION
                new V1Role(),                                    // NAMESPACE_SCOPE_FOUNDATION
                new ExtensionsV1beta1Ingress(),                  // FRONTEND
                new V1ServiceAccount(),                          // CLUSTER_SCOPE_FOUNDATION
                new V1StatefulSet(),                             // WORKLOAD
                new V1Service()                                  // WORKLOAD
        ));
    }

    private static io.kubernetes.client.openapi.models.V1ObjectMeta meta(String name) {
        return new io.kubernetes.client.openapi.models.V1ObjectMeta().name(name);
    }

    @Test
    public void testApplyOrderGroupsResourcesByLayer() {
        List<Object> input = shuffledSample();
        List<Object> sorted = ResourceOrdering.sortForApply(input);

        assertEquals(input.size(), sorted.size());
        assertTrue(sorted.indexOf(input.get(3)) == 0); // Namespace ends up first

        List<ResourceOrdering.Layer> actualLayers = layersOf(sorted);
        List<ResourceOrdering.Layer> expectedLayers = Arrays.asList(
                ResourceOrdering.Layer.NAMESPACE,
                ResourceOrdering.Layer.CLUSTER_SCOPE_FOUNDATION,
                ResourceOrdering.Layer.CLUSTER_SCOPE_FOUNDATION,
                ResourceOrdering.Layer.CLUSTER_SCOPE_FOUNDATION,
                ResourceOrdering.Layer.NAMESPACE_SCOPE_FOUNDATION,
                ResourceOrdering.Layer.NAMESPACE_SCOPE_FOUNDATION,
                ResourceOrdering.Layer.NAMESPACE_SCOPE_FOUNDATION,
                ResourceOrdering.Layer.NAMESPACE_SCOPE_FOUNDATION,
                ResourceOrdering.Layer.NAMESPACE_SCOPE_FOUNDATION,
                ResourceOrdering.Layer.ROLE_BINDING,
                ResourceOrdering.Layer.ROLE_BINDING,
                ResourceOrdering.Layer.WORKLOAD,
                ResourceOrdering.Layer.WORKLOAD,
                ResourceOrdering.Layer.WORKLOAD,
                ResourceOrdering.Layer.WORKLOAD,
                ResourceOrdering.Layer.FRONTEND,
                ResourceOrdering.Layer.FRONTEND,
                ResourceOrdering.Layer.FRONTEND);
        assertEquals(expectedLayers, actualLayers);
    }

    @Test
    public void testSameLayerResourcesKeepOriginalOrder() {
        // The two Secrets (secret-a before secret-b) must survive the sort unchanged.
        List<Object> sorted = ResourceOrdering.sortForApply(shuffledSample());

        Object secretA = null;
        Object secretB = null;
        for (Object resource : sorted) {
            if (resource instanceof V1Secret) {
                String name = ((V1Secret) resource).getMetadata().getName();
                if ("secret-a".equals(name)) {
                    secretA = resource;
                } else if ("secret-b".equals(name)) {
                    secretB = resource;
                }
            }
        }
        assertTrue(sorted.indexOf(secretA) < sorted.indexOf(secretB));
    }

    @Test
    public void testSortIsStableAcrossDifferentShuffles() {
        List<Object> first = ResourceOrdering.sortForApply(shuffledSample());

        List<Object> reversedInput = new ArrayList<>(shuffledSample());
        Collections.reverse(reversedInput);
        List<Object> second = ResourceOrdering.sortForApply(reversedInput);

        // Both runs group by the same layer sequence.
        assertEquals(layersOf(first), layersOf(second));
        // Within a layer the input order is preserved, so the reversed input reverses the
        // in-layer sequence, but the Namespace is still first in both outputs.
        assertTrue(first.get(0) instanceof V1Namespace);
        assertTrue(second.get(0) instanceof V1Namespace);
    }

    @Test
    public void testDeleteOrderReversesTheLayers() {
        List<Object> input = shuffledSample();
        List<Object> applied = ResourceOrdering.sortForApply(input);
        List<Object> deleted = ResourceOrdering.sortForDelete(input);

        List<ResourceOrdering.Layer> applyLayers = layersOf(applied);
        List<ResourceOrdering.Layer> deleteLayers = layersOf(deleted);
        Collections.reverse(applyLayers);
        assertEquals(applyLayers, deleteLayers);

        // Namespace must be the very last deletion.
        assertTrue(deleted.get(deleted.size() - 1) instanceof V1Namespace);
    }

    @Test
    public void testDeleteKeepsRelativeOrderInsideALayer() {
        List<Object> input = shuffledSample();
        List<Object> deleted = ResourceOrdering.sortForDelete(input);

        Object secretA = null;
        Object secretB = null;
        for (Object resource : deleted) {
            if (resource instanceof V1Secret) {
                String name = ((V1Secret) resource).getMetadata().getName();
                if ("secret-a".equals(name)) {
                    secretA = resource;
                } else if ("secret-b".equals(name)) {
                    secretB = resource;
                }
            }
        }
        assertTrue(deleted.indexOf(secretA) < deleted.indexOf(secretB));
    }

    @Test
    public void testUnknownTypesGoAfterEveryKnownLayer() {
        Object custom = new Object();
        List<Object> input = new ArrayList<>(Arrays.asList(
                custom,
                new V1Deployment(),
                new V1Namespace()));

        List<Object> sorted = ResourceOrdering.sortForApply(input);
        assertEquals(V1Namespace.class, sorted.get(0).getClass());
        assertEquals(V1Deployment.class, sorted.get(1).getClass());
        assertSameObject(custom, sorted.get(2));

        // In delete mode unknown resources are removed first.
        List<Object> deleted = ResourceOrdering.sortForDelete(input);
        assertSameObject(custom, deleted.get(0));
        assertTrue(deleted.get(deleted.size() - 1) instanceof V1Namespace);
    }

    private void assertSameObject(Object expected, Object actual) {
        assertTrue("expected the same unknown object instance", expected == actual);
    }

    @Test
    public void testInputListIsNotModifiedAndNewListIsReturned() {
        List<Object> input = shuffledSample();
        List<Object> snapshot = new ArrayList<>(input);

        List<Object> sorted = ResourceOrdering.sortForApply(input);

        assertEquals(snapshot, input);
        assertNotSame(input, sorted);
    }

    @Test
    public void testEmptyAndNullElementsAreHandled() {
        assertTrue(ResourceOrdering.sortForApply(Collections.emptyList()).isEmpty());

        List<Object> withNull = new ArrayList<>(Collections.singletonList(null));
        List<Object> sorted = ResourceOrdering.sortForApply(withNull);
        assertEquals(1, sorted.size());
        assertTrue(sorted.get(0) == null);
    }

    @Test
    public void testEveryUpdaterSupportedKindHasAnExplicitLayer() {
        // Every kind that handleResource can dispatch to must have an explicit layer, otherwise
        // it would silently fall into the trailing UNKNOWN layer.
        for (Class<?> kind : ResourceUpdaterMap.getUnmodifiableInstance().keySet()) {
            assertTrue("missing layer mapping for " + kind.getName(),
                    ResourceOrdering.getLayerMapping().containsKey(kind));
        }
    }

    @Test
    public void testLayerMappingCoversRepresentativesOfEveryLayer() {
        Map<Class<?>, ResourceOrdering.Layer> mapping = ResourceOrdering.getLayerMapping();
        assertEquals(ResourceOrdering.Layer.NAMESPACE, mapping.get(V1Namespace.class));
        assertEquals(ResourceOrdering.Layer.CLUSTER_SCOPE_FOUNDATION,
                mapping.get(V1PersistentVolume.class));
        assertEquals(ResourceOrdering.Layer.CLUSTER_SCOPE_FOUNDATION, mapping.get(V1ClusterRole.class));
        assertEquals(ResourceOrdering.Layer.CLUSTER_SCOPE_FOUNDATION,
                mapping.get(V1ServiceAccount.class));
        assertEquals(ResourceOrdering.Layer.NAMESPACE_SCOPE_FOUNDATION, mapping.get(V1Role.class));
        assertEquals(ResourceOrdering.Layer.NAMESPACE_SCOPE_FOUNDATION, mapping.get(V1ConfigMap.class));
        assertEquals(ResourceOrdering.Layer.NAMESPACE_SCOPE_FOUNDATION, mapping.get(V1Secret.class));
        assertEquals(ResourceOrdering.Layer.NAMESPACE_SCOPE_FOUNDATION,
                mapping.get(V1PersistentVolumeClaim.class));
        assertEquals(ResourceOrdering.Layer.ROLE_BINDING, mapping.get(V1RoleBinding.class));
        assertEquals(ResourceOrdering.Layer.ROLE_BINDING, mapping.get(V1ClusterRoleBinding.class));
        assertEquals(ResourceOrdering.Layer.WORKLOAD, mapping.get(V1Service.class));
        assertEquals(ResourceOrdering.Layer.WORKLOAD, mapping.get(V1Pod.class));
        assertEquals(ResourceOrdering.Layer.WORKLOAD,
                mapping.get(ExtensionsV1beta1Deployment.class));
        assertEquals(ResourceOrdering.Layer.FRONTEND,
                mapping.get(V1HorizontalPodAutoscaler.class));
        assertEquals(ResourceOrdering.Layer.FRONTEND, mapping.get(ExtensionsV1beta1Ingress.class));
    }

    private List<ResourceOrdering.Layer> layersOf(List<Object> resources) {
        List<ResourceOrdering.Layer> layers = new ArrayList<>();
        for (Object resource : resources) {
            ResourceOrdering.Layer layer = resource == null
                    ? ResourceOrdering.Layer.UNKNOWN
                    : ResourceOrdering.getLayerMapping().get(resource.getClass());
            layers.add(layer == null ? ResourceOrdering.Layer.UNKNOWN : layer);
        }
        return layers;
    }
}
