/*
 * Copyright 2023 Red Hat
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

package io.apicurio.registry.deployment;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.LocalPortForward;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

import static io.apicurio.registry.deployment.KubernetesTestResources.APPLICATION_IN_MEMORY_RESOURCES;
import static io.apicurio.registry.deployment.KubernetesTestResources.APPLICATION_IN_MEMORY_SECURED_RESOURCES;
import static io.apicurio.registry.deployment.KubernetesTestResources.APPLICATION_KAFKA_RESOURCES;
import static io.apicurio.registry.deployment.KubernetesTestResources.APPLICATION_KAFKA_SECURED_RESOURCES;
import static io.apicurio.registry.deployment.KubernetesTestResources.APPLICATION_SERVICE;
import static io.apicurio.registry.deployment.KubernetesTestResources.APPLICATION_SQL_RESOURCES;
import static io.apicurio.registry.deployment.KubernetesTestResources.APPLICATION_SQL_SECURED_RESOURCES;
import static io.apicurio.registry.deployment.KubernetesTestResources.DATABASE_RESOURCES;
import static io.apicurio.registry.deployment.KubernetesTestResources.E2E_NAMESPACE_RESOURCE;
import static io.apicurio.registry.deployment.KubernetesTestResources.KAFKA_RESOURCES;
import static io.apicurio.registry.deployment.KubernetesTestResources.KEYCLOAK_RESOURCES;
import static io.apicurio.registry.deployment.KubernetesTestResources.KEYCLOAK_SERVICE;
import static io.apicurio.registry.deployment.KubernetesTestResources.TEST_NAMESPACE;

public class RegistryDeploymentManager implements BeforeAllCallback, AfterAllCallback {

    private static final Logger LOGGER = LoggerFactory.getLogger(RegistryDeploymentManager.class);

    KubernetesClient kubernetesClient;
    LocalPortForward registryPortForward;
    LocalPortForward keycloakPortForward;

    @Override
    public void beforeAll(ExtensionContext extensionContext) throws Exception {
        if (Boolean.parseBoolean(System.getProperty("cluster.tests"))) {

            handleInfraDeployment();

            LOGGER.info("Test suite started ##################################################");
        }
    }

    private void handleInfraDeployment() {
        kubernetesClient = new KubernetesClientBuilder()
                .build();

        //First, create the namespace used for the test.
        kubernetesClient.load(getClass().getResourceAsStream(E2E_NAMESPACE_RESOURCE))
                .create();

        //Based on the configuration, dpeloy the appropriate variant
        if (Boolean.parseBoolean(System.getProperty("deployInMemory"))) {
            deployInMemoryApp();
        } else if (Boolean.parseBoolean(System.getProperty("deploySql"))) {
            deploySqlApp();
        } else if (Boolean.parseBoolean(System.getProperty("deployKafka"))) {
            deployKafkaApp();
        }

        //No matter the storage type, create port forward so the application is reachable from the tests
        registryPortForward = kubernetesClient.services()
                .inNamespace(TEST_NAMESPACE)
                .withName(APPLICATION_SERVICE)
                .portForward(8080, 8080);
    }


    private void deployInMemoryApp() {
        if (Constants.TEST_PROFILE.equals(Constants.AUTH)) {
            startResources(null, APPLICATION_IN_MEMORY_SECURED_RESOURCES, true);
        } else {
            startResources(null, APPLICATION_IN_MEMORY_RESOURCES, false);
        }
    }

    private void deployKafkaApp() {
        if (Constants.TEST_PROFILE.equals(Constants.AUTH)) {
            startResources(KAFKA_RESOURCES, APPLICATION_KAFKA_SECURED_RESOURCES, true);
        } else {
            startResources(KAFKA_RESOURCES, APPLICATION_KAFKA_RESOURCES, false);
        }
    }

    private void deploySqlApp() {
        if (Constants.TEST_PROFILE.equals(Constants.AUTH)) {
            startResources(DATABASE_RESOURCES, APPLICATION_SQL_SECURED_RESOURCES, true);
        } else {
            startResources(DATABASE_RESOURCES, APPLICATION_SQL_RESOURCES, false);
        }
    }

    private void startResources(String externalResources, String registryResources, boolean startKeycloak) {
        if (startKeycloak) {
            //Deploy all the resources associated to the external requirements
            kubernetesClient.load(getClass().getResourceAsStream(KEYCLOAK_RESOURCES))
                    .create();

            //Wait for all the external resources pods to be ready
            kubernetesClient.pods()
                    .inNamespace(TEST_NAMESPACE).waitUntilReady(30, TimeUnit.SECONDS);

            //Create the keycloak port forward so the tests can reach it to get tokens
            keycloakPortForward = kubernetesClient.services()
                    .inNamespace(TEST_NAMESPACE)
                    .withName(KEYCLOAK_SERVICE)
                    .portForward(8090, 8090);
        }

        if (externalResources != null) {
            //Deploy all the resources associated to the external requirements
            kubernetesClient.load(getClass().getResourceAsStream(externalResources))
                    .create();

            //Wait for all the external resources pods to be ready
            kubernetesClient.pods()
                    .inNamespace(TEST_NAMESPACE).waitUntilReady(30, TimeUnit.SECONDS);
        }

        //Deploy all the resources associated to the registry variant
        kubernetesClient.load(getClass().getResourceAsStream(registryResources))
                .create();

        //Wait for all the pods of the variant to be ready
        kubernetesClient.pods()
                .inNamespace(TEST_NAMESPACE).waitUntilReady(30, TimeUnit.SECONDS);
    }

    @Override
    public void afterAll(ExtensionContext extensionContext) throws Exception {
        LOGGER.info("Test suite ended ##################################################");

        if (registryPortForward != null) {
            registryPortForward.close();
        }

        if (keycloakPortForward != null) {
            keycloakPortForward.close();
        }

        //Finally, once the testsuite is done, cleanup all the resources in the cluster
        if (kubernetesClient != null) {
            LOGGER.info("Closing test resources ##################################################");
            kubernetesClient.namespaces()
                    .withName(TEST_NAMESPACE)
                    .delete();

            kubernetesClient.close();
        }
    }
}