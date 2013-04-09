package edu.uccs.cs.cs5050.spotmarket;

import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.*;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancingClient;
import com.amazonaws.services.elasticloadbalancing.model.*;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;

public class SpotInstanceMonitor {
    @Autowired
    private AmazonEC2Client ec2Client;

    @Autowired
    private AmazonElasticLoadBalancingClient loadBalancingClient;

    private Map<String, SpotInstanceRequest> requestMap = new HashMap<String, SpotInstanceRequest>();
    private static final Logger logger = Logger.getLogger(SpotInstanceMonitor.class);
    private static final String LOAD_BALANCER_NAME = "vieira-loadbalancer";

    public void monitor() {
        // instanceIds of current spot request instances
        Collection<String> instanceIds = new ArrayList<String>();

        // get spot instance request data
        DescribeSpotInstanceRequestsResult spotInstanceRequestsResult = ec2Client.describeSpotInstanceRequests();
        for (SpotInstanceRequest spotInstanceRequest : spotInstanceRequestsResult.getSpotInstanceRequests()) {
            SpotInstanceRequest previousRequest = requestMap.get(spotInstanceRequest.getSpotInstanceRequestId());

            if (previousRequest == null) {
                logger.info("Found spot instance request: " + spotInstanceRequest.getSpotInstanceRequestId() + " [" + spotInstanceRequest.getState() + ":" + spotInstanceRequest.getStatus().toString() + "]");
            } else if (!previousRequest.getState().equalsIgnoreCase(spotInstanceRequest.getState()) ||
                    !previousRequest.getStatus().getCode().equalsIgnoreCase(spotInstanceRequest.getStatus().getCode())) {
                logger.info(spotInstanceRequest.getSpotInstanceRequestId() + " status changed: " + previousRequest.getState() + "[" + previousRequest.getStatus().getCode() +
                        "] -> " + spotInstanceRequest.getState() + "[" + spotInstanceRequest.getStatus().getCode() + "]");

            }

            if (spotInstanceRequest.getInstanceId() != null) {
                instanceIds.add(spotInstanceRequest.getInstanceId());
            }

            requestMap.put(spotInstanceRequest.getSpotInstanceRequestId(), spotInstanceRequest);
        }

        // get load balancer data
        DescribeLoadBalancersRequest loadBalancersRequest = new DescribeLoadBalancersRequest();
        loadBalancersRequest.setLoadBalancerNames(Arrays.asList(LOAD_BALANCER_NAME));
        DescribeLoadBalancersResult loadBalancersResult = loadBalancingClient.describeLoadBalancers(loadBalancersRequest);
        LoadBalancerDescription loadBalancerDescription = loadBalancersResult.getLoadBalancerDescriptions().get(0);

        // get instance data
        if (instanceIds.size() > 0) {
            DescribeInstancesRequest instancesRequest = new DescribeInstancesRequest();
            instancesRequest.setInstanceIds(instanceIds);
            DescribeInstancesResult describeInstancesResult = ec2Client.describeInstances(instancesRequest);
            for (Reservation reservation : describeInstancesResult.getReservations()) {
                for (Instance instance : reservation.getInstances()) {
                    boolean running = InstanceStateName.fromValue(instance.getState().getName()) == InstanceStateName.Running;

                    // check to see if this instance is registered with our load balancer
                    boolean registered = false;
                    for (com.amazonaws.services.elasticloadbalancing.model.Instance loadBalancerInstance : loadBalancerDescription.getInstances()) {
                        if (instance.getInstanceId().equalsIgnoreCase(loadBalancerInstance.getInstanceId())) {
                            registered = true;
                            break;
                        }
                    }

                    // if the instance is running but not registered with load balancer then register it
                    if (running && !registered) {
                        logger.info("Registering instance [" + instance.getInstanceId() + "] with load balancer.");
                        RegisterInstancesWithLoadBalancerRequest registerInstancesRequest = new RegisterInstancesWithLoadBalancerRequest();

                        Collection<com.amazonaws.services.elasticloadbalancing.model.Instance> instancesToRegister = new ArrayList<com.amazonaws.services.elasticloadbalancing.model.Instance>();
                        instancesToRegister.add(new com.amazonaws.services.elasticloadbalancing.model.Instance(instance.getInstanceId()));
                        registerInstancesRequest.setInstances(instancesToRegister);
                        registerInstancesRequest.setLoadBalancerName(LOAD_BALANCER_NAME);

                        loadBalancingClient.registerInstancesWithLoadBalancer(registerInstancesRequest);
                    } else if (!running && registered) {
                        logger.info("Deregistering instance [" + instance.getInstanceId() + "] with load balancer.");
                        DeregisterInstancesFromLoadBalancerRequest deregisterInstancesRequest = new DeregisterInstancesFromLoadBalancerRequest();

                        Collection<com.amazonaws.services.elasticloadbalancing.model.Instance> instancesToDeregister = new ArrayList<com.amazonaws.services.elasticloadbalancing.model.Instance>();
                        instancesToDeregister.add(new com.amazonaws.services.elasticloadbalancing.model.Instance(instance.getInstanceId()));
                        deregisterInstancesRequest.setInstances(instancesToDeregister);
                        deregisterInstancesRequest.setLoadBalancerName(LOAD_BALANCER_NAME);

                        loadBalancingClient.deregisterInstancesFromLoadBalancer(deregisterInstancesRequest);
                    }
                }
            }
        }

        // find removed requests
        Iterator<Map.Entry<String, SpotInstanceRequest>> iterator = requestMap.entrySet().iterator();
        while (iterator.hasNext()) {
            boolean found = false;
            Map.Entry<String, SpotInstanceRequest> spotInstanceRequestEntry = iterator.next();
            String requestId = spotInstanceRequestEntry.getKey();
            for (SpotInstanceRequest request : spotInstanceRequestsResult.getSpotInstanceRequests()) {
                if (requestId.equalsIgnoreCase(request.getSpotInstanceRequestId())) {
                    found = true;
                    break;
                }
            }

            if (!found) {
                logger.info("Spot instance request has been removed: " + requestId);
                iterator.remove();
            }
        }
    }
}