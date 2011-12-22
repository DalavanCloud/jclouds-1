/**
 * Licensed to jclouds, Inc. (jclouds) under one or more
 * contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  jclouds licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.jclouds.vcloud.compute.strategy;

import static com.google.common.base.Preconditions.checkNotNull;

import java.net.URI;
import java.util.Map;
import java.util.Set;

import javax.annotation.Resource;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.jclouds.compute.ComputeService;
import org.jclouds.compute.ComputeServiceAdapter;
import org.jclouds.compute.domain.Template;
import org.jclouds.compute.reference.ComputeServiceConstants;
import org.jclouds.domain.Location;
import org.jclouds.logging.Logger;
import org.jclouds.vcloud.VCloudClient;
import org.jclouds.vcloud.VCloudMediaType;
import org.jclouds.vcloud.compute.suppliers.OrgAndVDCToLocationSupplier;
import org.jclouds.vcloud.domain.Org;
import org.jclouds.vcloud.domain.ReferenceType;
import org.jclouds.vcloud.domain.Status;
import org.jclouds.vcloud.domain.Task;
import org.jclouds.vcloud.domain.VApp;
import org.jclouds.vcloud.domain.VAppTemplate;
import org.jclouds.vcloud.suppliers.VAppTemplatesSupplier;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Predicate;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSet.Builder;

/**
 * defines the connection between the {@link VCloudClient} implementation and the jclouds
 * {@link ComputeService}
 * 
 */
@Singleton
public class VCloudComputeServiceAdapter implements ComputeServiceAdapter<VApp, VAppTemplate, VAppTemplate, Location> {

   @Resource
   @Named(ComputeServiceConstants.COMPUTE_LOGGER)
   protected Logger logger = Logger.NULL;

   protected final VCloudClient client;
   protected final Predicate<URI> successTester;
   protected final InstantiateVAppTemplateWithGroupEncodedIntoNameThenCustomizeDeployAndPowerOn booter;
   protected final Supplier<Map<String, Org>> nameToOrg;
   protected final Supplier<Set<VAppTemplate>> templates;
   protected final Supplier<Set<? extends Location>> locations;

   @Inject
   protected VCloudComputeServiceAdapter(VCloudClient client, Predicate<URI> successTester,
            InstantiateVAppTemplateWithGroupEncodedIntoNameThenCustomizeDeployAndPowerOn booter,
            Supplier<Map<String, Org>> nameToOrg,  VAppTemplatesSupplier templates,
            OrgAndVDCToLocationSupplier locations) {
      this.client = checkNotNull(client, "client");
      this.successTester = checkNotNull(successTester, "successTester");
      this.booter = checkNotNull(booter, "booter");
      this.nameToOrg = checkNotNull(nameToOrg, "nameToOrg");
      this.templates = checkNotNull(templates, "templates");
      this.locations = checkNotNull(locations, "locations");
   }

   @Override
   public NodeAndInitialCredentials<VApp> createNodeWithGroupEncodedIntoName(String group, String name,
            Template template) {
      return booter.createNodeWithGroupEncodedIntoName(group, name, template);
   }

   @Override
   public Iterable<VAppTemplate> listHardwareProfiles() {
      return templates.get();
   }

   @Override
   public Iterable<VAppTemplate> listImages() {
      return templates.get();
   }

   @Override
   public Iterable<VApp> listNodes() {
      // TODO: parallel or cache
      Builder<VApp> nodes = ImmutableSet.<VApp> builder();
      for (Org org : nameToOrg.get().values()) {
         for (ReferenceType vdc : org.getVDCs().values()) {
            for (ReferenceType resource : client.getVDCClient().getVDC(vdc.getHref()).getResourceEntities().values()) {
               if (resource.getType().equals(VCloudMediaType.VAPP_XML)) {
                  addVAppToSetRetryingIfNotYetPresent(nodes, vdc, resource);
               }
            }
         }
      }
      return nodes.build();
   }

   @VisibleForTesting
   void addVAppToSetRetryingIfNotYetPresent(Builder<VApp> nodes, ReferenceType vdc, ReferenceType resource) {
      VApp node = null;
      int i = 0;
      while (node == null && i++ < 3) {
         try {
            node = client.getVAppClient().getVApp(resource.getHref());
            nodes.add(node);
         } catch (NullPointerException e) {
            logger.warn("vApp %s not yet present in vdc %s", resource.getName(), vdc.getName());
         }
      }
   }

   @SuppressWarnings("unchecked")
   @Override
   public Iterable<Location> listLocations() {
      return (Iterable<Location>) locations.get();
   }

   @Override
   public VApp getNode(String in) {
      URI id = URI.create(in);
      return client.getVAppClient().getVApp(id);

   }

   @Override
   public void destroyNode(String id) {
      URI vappId = URI.create(checkNotNull(id, "node.id"));
      VApp vApp = client.getVAppClient().getVApp(vappId);

      waitForPendingTasksToComplete(vApp);

      vApp = undeployVAppIfDeployed(vApp);
      deleteVApp(vApp);
   }

   void waitForPendingTasksToComplete(VApp vApp) {
      for (Task task : vApp.getTasks())
         waitForTask(task, vApp);
   }

   public void waitForTask(Task task, VApp vAppResponse) {
      if (!successTester.apply(task.getHref())) {
         throw new RuntimeException(String.format("failed to %s %s: %s", task.getName(), vAppResponse.getName(), task));
      }
   }

   void deleteVApp(VApp vApp) {
      logger.debug(">> deleting vApp(%s)", vApp.getHref());
      waitForTask(client.getVAppClient().deleteVApp(vApp.getHref()), vApp);
      logger.debug("<< deleted vApp(%s)", vApp.getHref());
   }

   VApp undeployVAppIfDeployed(VApp vApp) {
      if (vApp.getStatus() != Status.OFF) {
         logger.debug(">> undeploying vApp(%s), current status: %s", vApp.getName(), vApp.getStatus());
         try {
            waitForTask(client.getVAppClient().undeployVApp(vApp.getHref()), vApp);
            vApp = client.getVAppClient().getVApp(vApp.getHref());
            logger.debug("<< %s vApp(%s)", vApp.getStatus(), vApp.getName());
         } catch (IllegalStateException e) {
            logger.warn(e, "<< %s vApp(%s)", vApp.getStatus(), vApp.getName());
         }
      }
      return vApp;
   }

   @Override
   public void rebootNode(String in) {
      URI id = URI.create(checkNotNull(in, "node.id"));
      Task task = client.getVAppClient().resetVApp(id);
      successTester.apply(task.getHref());

   }

   @Override
   public void resumeNode(String in) {
      URI id = URI.create(checkNotNull(in, "node.id"));
      Task task = client.getVAppClient().powerOnVApp(id);
      successTester.apply(task.getHref());
   }

   @Override
   public void suspendNode(String in) {
      URI id = URI.create(checkNotNull(in, "node.id"));
      Task task = client.getVAppClient().powerOffVApp(id);
      successTester.apply(task.getHref());
   }
}