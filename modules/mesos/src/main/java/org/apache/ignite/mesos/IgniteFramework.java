/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.mesos;

import com.google.protobuf.*;
import org.apache.ignite.mesos.resource.*;
import org.apache.mesos.*;
import org.glassfish.grizzly.http.server.*;
import org.glassfish.jersey.grizzly2.httpserver.*;
import org.glassfish.jersey.server.*;

import java.net.*;

/**
 * Ignite mesos framework.
 */
public class IgniteFramework {

    public static final String IGNITE_FRAMEWORK_NAME = "Ignite";

    /**
     * Main methods has only one optional parameter - path to properties files.
     *
     * @param args Args.
     */
    public static void main(String[] args) throws Exception {
        final int frameworkFailoverTimeout = 0;

        // Have Mesos fill in the current user.
        Protos.FrameworkInfo.Builder frameworkBuilder = Protos.FrameworkInfo.newBuilder()
            .setName(IGNITE_FRAMEWORK_NAME)
            .setUser("")
            .setFailoverTimeout(frameworkFailoverTimeout);

        if (System.getenv("MESOS_CHECKPOINT") != null) {
            System.out.println("Enabling checkpoint for the framework");
            frameworkBuilder.setCheckpoint(true);
        }

        ClusterProperties clusterProps = ClusterProperties.from(args.length == 1 ? args[0] : null);

        String baseUrl = String.format("http://%s:%d", clusterProps.httpServerHost(), clusterProps.httpServerPort());

        URI httpServerBaseUri = URI.create(baseUrl);

        ResourceConfig rc = new ResourceConfig()
            .registerInstances(new ResourceController(clusterProps.userLibs(), clusterProps.igniteCfg(),
                    clusterProps.igniteWorkDir()));

        HttpServer httpServer = GrizzlyHttpServerFactory.createHttpServer(httpServerBaseUri, rc);

        ResourceProvider provider = new ResourceProvider();

        IgniteProvider igniteProvider = new IgniteProvider(clusterProps.igniteWorkDir());

        provider.init(clusterProps, igniteProvider, baseUrl);

        // Create the scheduler.
        Scheduler scheduler = new IgniteScheduler(clusterProps, provider);

        // create the driver
        MesosSchedulerDriver driver;
        if (System.getenv("MESOS_AUTHENTICATE") != null) {
            System.out.println("Enabling authentication for the framework");

            if (System.getenv("DEFAULT_PRINCIPAL") == null) {
                System.err.println("Expecting authentication principal in the environment");
                System.exit(1);
            }

            if (System.getenv("DEFAULT_SECRET") == null) {
                System.err.println("Expecting authentication secret in the environment");
                System.exit(1);
            }

            Protos.Credential credential = Protos.Credential.newBuilder()
                .setPrincipal(System.getenv("DEFAULT_PRINCIPAL"))
                .setSecret(ByteString.copyFrom(System.getenv("DEFAULT_SECRET").getBytes()))
                .build();

            frameworkBuilder.setPrincipal(System.getenv("DEFAULT_PRINCIPAL"));

            driver = new MesosSchedulerDriver(scheduler, frameworkBuilder.build(), clusterProps.masterUrl(),
                credential);
        }
        else {
            frameworkBuilder.setPrincipal("ignite-framework-java");

            driver = new MesosSchedulerDriver(scheduler, frameworkBuilder.build(), clusterProps.masterUrl());
        }

        int status = driver.run() == Protos.Status.DRIVER_STOPPED ? 0 : 1;

        httpServer.shutdown();

        // Ensure that the driver process terminates.
        driver.stop();

        System.exit(status);
    }
}
