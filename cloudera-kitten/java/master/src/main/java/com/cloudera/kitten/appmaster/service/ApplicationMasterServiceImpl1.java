/**
 * Copyright (c) 2012, Cloudera, Inc. All Rights Reserved.
 *
 * Cloudera, Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"). You may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for
 * the specific language governing permissions and limitations under the
 * License.
 */
package com.cloudera.kitten.appmaster.service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.cloudera.kitten.ContainerLaunchContextFactory;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.yarn.api.protocolrecords.RegisterApplicationMasterResponse;
import org.apache.hadoop.yarn.api.records.Container;
import org.apache.hadoop.yarn.api.records.ContainerExitStatus;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.api.records.ContainerLaunchContext;
import org.apache.hadoop.yarn.api.records.ContainerStatus;
import org.apache.hadoop.yarn.api.records.FinalApplicationStatus;
import org.apache.hadoop.yarn.api.records.NodeReport;
import org.apache.hadoop.yarn.api.records.Priority;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.client.api.AMRMClient;
import org.apache.hadoop.yarn.client.api.YarnClientApplication;
import org.apache.hadoop.yarn.client.api.async.AMRMClientAsync;
import org.apache.hadoop.yarn.client.api.async.NMClientAsync;
import org.apache.hadoop.yarn.conf.YarnConfiguration;

import com.cloudera.kitten.ContainerLaunchParameters;
import com.cloudera.kitten.appmaster.ApplicationMasterParameters;
import com.cloudera.kitten.appmaster.ApplicationMasterService;
import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.AbstractScheduledService;

public class ApplicationMasterServiceImpl1 extends
    AbstractScheduledService implements ApplicationMasterService,
    AMRMClientAsync.CallbackHandler {

  private static final Log LOG = LogFactory.getLog(ApplicationMasterServiceImpl1.class);

  private final ApplicationMasterParameters parameters;
  private final YarnConfiguration conf;
  private final AtomicInteger totalFailures = new AtomicInteger();
  private final List<ContainerTracker> trackers = Lists.newArrayList();
  private HashMap<ContainerId, ContainerTracker> containerAllocation;

  private AMRMClientAsync resourceManager;
  private boolean hasRunningContainers = false;
  private Throwable throwable;
 

protected ContainerLaunchContextFactory factory;

  public ApplicationMasterServiceImpl1(ApplicationMasterParameters parameters, Configuration conf) {
    this.parameters = Preconditions.checkNotNull(parameters);
    this.conf = new YarnConfiguration(conf);
  }

  @Override
  public ApplicationMasterParameters getParameters() {
    return parameters;
  }

  @Override
  public boolean hasRunningContainers() {
    return hasRunningContainers;
  }
  
  @Override
  protected void startUp() throws IOException {
	  this.containerAllocation = new HashMap<ContainerId, ContainerTracker>();
    this.resourceManager = AMRMClientAsync.createAMRMClientAsync(1000, this);
    this.resourceManager.init(conf);
    this.resourceManager.start();

    RegisterApplicationMasterResponse registration;
    try {
      registration = resourceManager.registerApplicationMaster(
          parameters.getHostname(),
          parameters.getClientPort(),
          parameters.getTrackingUrl());
    } catch (Exception e) {
      LOG.error("Exception thrown registering application master", e);
      stop();
      return;
    }

    factory = new ContainerLaunchContextFactory(
        registration.getMaximumResourceCapability());
    for (ContainerLaunchParameters clp : parameters.getContainerLaunchParameters().values()) {
      ContainerTracker tracker = new ContainerTracker(clp);
      tracker.init(factory);
      trackers.add(tracker);
    }
    /*ContainerTracker prevTracker = trackers.get(0);
    int i=0;
    for(ContainerTracker t:trackers){
    	if(i>0)
    		prevTracker.addNextTracker(t);
    	i++;
    }
    trackers.get(0).init(factory);
    */
    this.hasRunningContainers = true;
  }
  
  @Override
  protected void shutDown() {
    // Stop the containers in the case that we're finishing because of a timeout.
    LOG.info("Stopping trackers");
    this.hasRunningContainers = false;

    for (ContainerTracker tracker : trackers) {
      if (tracker.hasRunningContainers()) {
        tracker.kill();
      }
    }
 
    FinalApplicationStatus status;
    String message = null;
    if (state() == State.FAILED || totalFailures.get() > parameters.getAllowedFailures()) {
      //TODO: diagnostics
      status = FinalApplicationStatus.FAILED;
      if (throwable != null) {
        message = throwable.getLocalizedMessage();
      }
    } else {
      status = FinalApplicationStatus.SUCCEEDED;
    }
    LOG.info("Sending finish request with status = " + status);
    try {
      resourceManager.unregisterApplicationMaster(status, message, null);
    } catch (Exception e) {
      LOG.error("Error finishing application master", e);
    }
  }

  @Override
  protected Scheduler scheduler() {
    return Scheduler.newFixedRateSchedule(0, 1, TimeUnit.SECONDS);
  }
  
  @Override
  protected void runOneIteration() throws Exception {
    if (totalFailures.get() > parameters.getAllowedFailures() ||
        allTrackersFinished()) {
      stop();
    }
  }

  private boolean allTrackersFinished() {
	  boolean ret = true;
	  for(ContainerTracker t : trackers){
		  if(t.hasMoreContainers()){
			 ret =false;
			 break;
		  }
	  }
	  LOG.info("allTrackersFinished: "+ret);
	  return ret;
  }

// AMRMClientHandler methods
  @Override
  public void onContainersCompleted(List<ContainerStatus> containerStatuses) {
    LOG.info(containerStatuses.size() + " containers have completed");
    for (ContainerStatus status : containerStatuses) {
      int exitStatus = status.getExitStatus();
      if (0 != exitStatus) {
        // container failed
        if (ContainerExitStatus.ABORTED != exitStatus) {
          totalFailures.incrementAndGet();
        } else {
          // container was killed by framework, possibly preempted
          // we should re-try as the container was lost for some reason
        }
      } else {
        // nothing to do
        // container completed successfully
    	  containerAllocation.get(status.getContainerId()).containerCompleted(status.getContainerId());
        LOG.info("Container id = " + status.getContainerId() + " completed successfully");
      }
    }
  }

  @Override
  public void onContainersAllocated(List<Container> allocatedContainers) {
    LOG.info("Allocating " + allocatedContainers.size() + " container(s)");
    Set<Container> assigned = Sets.newHashSet();
    for (ContainerTracker tracker : trackers) {
        for (Container allocated : allocatedContainers) {
            if (tracker.needsContainers()) {
	          if (!assigned.contains(allocated) && tracker.matches(allocated)) {
	            tracker.launchContainer(allocated);
	            assigned.add(allocated);
	            containerAllocation.put(allocated.getId(), tracker);
	          }
            }
        }
    }
    if (assigned.size() < allocatedContainers.size()) {
      LOG.error(String.format("Not all containers were allocated (%d out of %d)", assigned.size(),
          allocatedContainers.size()));
      stop();
    }
  }

  @Override
  public void onShutdownRequest() {
    stop();
  }

  @Override
  public void onNodesUpdated(List<NodeReport> nodeReports) {
    //TODO
  }

  @Override
  public float getProgress() {
    int num = 0, den = 0;
    for (ContainerTracker tracker : trackers) {
      num += tracker.completed.get();
      den += tracker.params.getNumInstances();
    }
    if (den == 0) {
      return 0.0f;
    }
    return ((float) num) / den;
  }

  @Override
  public void onError(Throwable throwable) {
    this.throwable = throwable;
    stop();
  }

  private class ContainerTracker implements NMClientAsync.CallbackHandler {
    private final ContainerLaunchParameters params;
    private final ConcurrentMap<ContainerId, Container> containers = Maps.newConcurrentMap();

    private AtomicInteger needed = new AtomicInteger();
    private AtomicInteger started = new AtomicInteger();
    private AtomicInteger completed = new AtomicInteger();
    private AtomicInteger failed = new AtomicInteger();
    private NMClientAsync nodeManager;
    private Resource resource;
    private Priority priority;
    private ContainerLaunchContext ctxt;
    private List<ContainerTracker> nextTrackers;

    public ContainerTracker(ContainerLaunchParameters parameters) {
      this.params = parameters;
      this.nextTrackers = new ArrayList<ContainerTracker>();
      needed.set(1);
    }

    public void addNextTracker(ContainerTracker tracker){
    	this.nextTrackers.add(tracker);
    }
    
    public void init(ContainerLaunchContextFactory factory) throws IOException {
      this.nodeManager = NMClientAsync.createNMClientAsync(this);
      nodeManager.init(conf);
      nodeManager.start();

      this.ctxt = factory.create(params);
      LOG.info("Init CTXT: " + ctxt.getLocalResources());
      LOG.info("Init CTXT: " + ctxt.getCommands());
      
      this.resource = factory.createResource(params);
      this.priority = factory.createPriority(params.getPriority());
      AMRMClient.ContainerRequest containerRequest = new AMRMClient.ContainerRequest(
          resource,
          null, // nodes
          null, // racks
          priority);
      int numInstances = params.getNumInstances();
      for (int j = 0; j < numInstances; j++) {
        resourceManager.addContainerRequest(containerRequest);
      }
      needed.set(numInstances);
    }

    @Override
    public void onContainerStarted(ContainerId containerId, Map<String, ByteBuffer> allServiceResponse) {
      Container container = containers.get(containerId);
      if (container != null) {
        LOG.info("Starting container id = " + containerId);
        started.incrementAndGet();
        nodeManager.getContainerStatusAsync(containerId, container.getNodeId());
      }
    }

    @Override
    public void onContainerStatusReceived(ContainerId containerId, ContainerStatus containerStatus) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Received status for container: " + containerId + " = " + containerStatus);
      }
    }

    @Override
    public void onContainerStopped(ContainerId containerId) {
      LOG.info("Stopping container id = " + containerId);
      Container v = containers.remove(containerId);
      if(v==null)
    	  return;
      completed.incrementAndGet();
      if(!hasMoreContainers()){
          LOG.info("Starting next trackers" );
    	  for(ContainerTracker t : nextTrackers){
    		  try {
				t.init(factory);
    		  } catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
    		  }
    	  }
      }
    }

    public void containerCompleted(ContainerId containerId) {
      LOG.info("Completed container id = " + containerId);
      containers.remove(containerId);
      completed.incrementAndGet();
      if(!hasMoreContainers()){
          LOG.info("Starting next trackers" );
    	  for(ContainerTracker t : nextTrackers){
    		  try {
				t.init(factory);
    		  } catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
    		  }
    	  }
      }
    }

    @Override
    public void onStartContainerError(ContainerId containerId, Throwable throwable) {
      LOG.warn("Start container error for container id = " + containerId, throwable);
      containers.remove(containerId);
      completed.incrementAndGet();
      failed.incrementAndGet();
    }

    @Override
    public void onGetContainerStatusError(ContainerId containerId, Throwable throwable) {
      LOG.error("Could not get status for container: " + containerId, throwable);
    }

    @Override
    public void onStopContainerError(ContainerId containerId, Throwable throwable) {
      LOG.error("Failed to stop container: " + containerId, throwable);
      completed.incrementAndGet();
    }

    public boolean needsContainers() {
        LOG.info("needed: "+needed);
        return needed.get() > 0;
    }

    public boolean matches(Container c) {
      return true; // TODO
    }

    public void launchContainer(Container c) {
      LOG.info("Launching container id = " + c.getId() + " on node = " + c.getNodeId());
      LOG.info("CTXT: "+ctxt.getLocalResources());
      LOG.info("CTXT: "+ctxt.getCommands());
      needed.decrementAndGet();
      containers.put(c.getId(), c);
      nodeManager.startContainerAsync(c, ctxt);
    }

    public boolean hasRunningContainers() {
      return !containers.isEmpty();
    }

    public void kill() {
      for (Container c : containers.values()) {
        nodeManager.stopContainerAsync(c.getId(), c.getNodeId());
      }
    }

    public boolean hasMoreContainers() {
      return needsContainers() || hasRunningContainers();
    }
  }
}
