/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.kubernetes.wrapper;

import io.kubernetes.client.openapi.models.V1ConfigMap;
import io.kubernetes.client.openapi.models.V1Deployment;
import io.kubernetes.client.openapi.models.V1Namespace;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1RoleBinding;
import io.kubernetes.client.openapi.models.V1Secret;
import io.kubernetes.client.openapi.models.V1Service;
import io.kubernetes.client.openapi.models.V1ServiceAccount;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the resource ordering integration inside {@link KubernetesClientWrapper}.
 *
 * <p>The wrapper is built through the package private no-arg constructor and
 * {@link KubernetesClientWrapper#handleResource(Object)} is replaced with a recording fake, so
 * no {@code ApiClient}, updater or cluster connection is created.</p>
 */
public class KubernetesClientWrapperOrderingTest {

    /**
     * Wrapper under test: instead of dispatching to a real updater it just remembers the order
     * in which resources are handed to it.
     */
    private static class RecordingWrapper extends KubernetesClientWrapper {
        private final List<Object> handled = new ArrayList<>();

        @Override
        void handleResource(Object resource) {
            handled.add(resource);
        }
    }

    private static V1ObjectMeta named(String name) {
        return new V1ObjectMeta().name(name);
    }

    @Test
    public void testApplyModeHandlesNamespaceAcrossFilesBeforeDeployment() {
        RecordingWrapper wrapper = new RecordingWrapper();

        // Two "files": the Deployment is encountered first, the Namespace only in the second one.
        V1Deployment deployment = new V1Deployment().metadata(named("web"));
        V1Service service = new V1Service().metadata(named("web-svc"));
        V1Namespace namespace = new V1Namespace().metadata(named("team-a"));
        List<Object> firstFile = Arrays.asList(deployment, service);
        List<Object> secondFile = Arrays.asList(namespace, new V1ConfigMap().metadata(named("cm")));

        List<Object> allResources = new ArrayList<>();
        allResources.addAll(firstFile);
        allResources.addAll(secondFile);

        List<Object> ordered = wrapper.orderResources(allResources);
        for (Object resource : ordered) {
            wrapper.handleResource(resource);
        }

        assertEquals(4, wrapper.handled.size());
        assertTrue("Namespace from the second file must be handled first",
                wrapper.handled.get(0) instanceof V1Namespace);
        assertTrue("ConfigMap precedes the workload layer",
                wrapper.handled.get(1) instanceof V1ConfigMap);
        assertTrue(wrapper.handled.get(2) instanceof V1Deployment);
        assertTrue(wrapper.handled.get(3) instanceof V1Service);
    }

    @Test
    public void testDeleteModeReversesLayerOrderAndDeletesNamespaceLast() {
        RecordingWrapper wrapper = new RecordingWrapper();
        wrapper.withDeleteResource(true);

        V1Namespace namespace = new V1Namespace().metadata(named("team-a"));
        V1Secret secret = new V1Secret().metadata(named("cred"));
        V1Deployment deployment = new V1Deployment().metadata(named("web"));
        V1Service service = new V1Service().metadata(named("web-svc"));
        List<Object> resources = Arrays.asList(namespace, secret, deployment, service);

        List<Object> ordered = wrapper.orderResources(resources);
        for (Object resource : ordered) {
            wrapper.handleResource(resource);
        }

        assertEquals(4, wrapper.handled.size());
        // Workload layer first, then the foundation layer, Namespace last.
        assertTrue(wrapper.handled.get(0) instanceof V1Deployment);
        assertTrue(wrapper.handled.get(1) instanceof V1Service);
        assertTrue(wrapper.handled.get(2) instanceof V1Secret);
        assertTrue("Namespace must be deleted last",
                wrapper.handled.get(3) instanceof V1Namespace);
    }

    @Test
    public void testTwoSecretsInTheSameLayerKeepTheirFileOrder() {
        RecordingWrapper wrapper = new RecordingWrapper();

        V1Secret first = new V1Secret().metadata(named("first-secret"));
        V1Secret second = new V1Secret().metadata(named("second-secret"));
        List<Object> resources = Arrays.asList(second, first);

        List<Object> ordered = wrapper.orderResources(resources);
        for (Object resource : ordered) {
            wrapper.handleResource(resource);
        }

        assertEquals(2, wrapper.handled.size());
        assertEquals("second-secret", ((V1Secret) wrapper.handled.get(0)).getMetadata().getName());
        assertEquals("first-secret", ((V1Secret) wrapper.handled.get(1)).getMetadata().getName());
    }

    @Test
    public void testShuffledInputAlwaysProducesTheSameLayerSequence() {
        RecordingWrapper wrapper = new RecordingWrapper();

        V1Namespace namespace = new V1Namespace().metadata(named("ns"));
        V1ServiceAccount serviceAccount = new V1ServiceAccount().metadata(named("sa"));
        V1ConfigMap configMap = new V1ConfigMap().metadata(named("cm"));
        V1RoleBinding roleBinding = new V1RoleBinding().metadata(named("rb"));
        V1Deployment deployment = new V1Deployment().metadata(named("dep"));
        V1Service service = new V1Service().metadata(named("svc"));

        List<Object> shuffled = new ArrayList<>(Arrays.asList(
                service, roleBinding, deployment, configMap, serviceAccount, namespace));
        List<Object> ordered = wrapper.orderResources(shuffled);

        // Service and Deployment share the WORKLOAD layer, so their input order (service first)
        // is preserved rather than being reordered.
        assertEquals(Arrays.asList(
                V1Namespace.class,
                V1ServiceAccount.class,
                V1ConfigMap.class,
                V1RoleBinding.class,
                V1Service.class,
                V1Deployment.class), classesOf(ordered));
    }

    private static List<Class<?>> classesOf(List<Object> resources) {
        List<Class<?>> classes = new ArrayList<>();
        for (Object resource : resources) {
            classes.add(resource.getClass());
        }
        return classes;
    }
}
