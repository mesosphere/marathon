---
title: Fault Domain Awareness and Capacity Extension
---

# Fault Domain Awareness

## Overview

A fault domain is a section of a network, for example, a rack in a datacenter or an entire datacenter, that is vulnerable to damage if a critical device or system fails. All instances within a fault domain share similar failure and latency characteristics. App or pod instances in the same fault domain are all affected by failure events within the domain. Placing instances in more than one fault domain reduces the risk that a failure will affect all instances.

Marathon supports fault domain awareness. Use fault domain awareness to make your apps or pods highly available and to allow for increased capacity when needed.

Marathon currently supports Mesos's 2-level hierarchical fault domains: zone and region.
	
## Zone fault domains
Zone fault domains offer a moderate degree of fault isolation because they share the same region. However, network latency between zones in the same region is moderately low (typically < 10ms).
	
For on-premise deployments, a zone would be a physical data center rack. 

For public cloud deployments, a zone would be the "availability zone” concept provided by most cloud providers.
	
If your goal is high-availability, and/or you are latency-sensitive, place your instances in a one region and balance them across zones.

## Region fault domains

Region fault domains offer the most fault isolation, though inter-region network latency is high (typically 50-100ms ). 
	 
For on-premise deployments, a region might be a data center.
	 
For public cloud deployments, most cloud providers expose a “region” concept.
	 
You can deploy your instances in a specific region region based on the available capacity.

### Local and remote regions

- The **local region** is the region running the Mesos master nodes.
- A **remote region** contains only Mesos agent nodes. There is usually high latency between a remote region and the local region.

## Mesos installation considerations

- Mesos master nodes must be in the same region because otherwise the latency between them will be too high. They should, however, be spread across zones for fault-tolerance.
- Marathon must run in the local region.
- You must have less than 100 ms latency between regions.

## Adding fault domain awareness to your app or pod definition

In your Marathon app or pod definition, you can use [placement constraints]({{ site.baseurl }}/docs/constraints.html)to:

- Specify a region and zone for your app or pod instances, so that all instances will be scheduled only in that region and zone.

- Specify a region without a specific zone, so that all instances will be scheduled in that region (but not necessarily in the same zone).

### Placement constraint guidelines

- If no region is specified in your app or pod definition, instances are only scheduled for the local region because of high latency between the local and remote region. No instance will ever be scheduled for an agent outside of the local region unless you explicitly specify.

- If a region is specified without a specific zone, instances are scheduled on any agent in the given region.

- If both region and zone are specified, instances are scheduled on any agent in the given region and zone, and not in any other region or zone.

- If you specify a hostname `UNIQUE` constraint, that constraint is also honored in remote regions.

### Examples

Suppose we have a Mesos cluster that spans 3 regions: `aws-us-east1`, `aws-us-east2`, and `local`. Each region has zones `a`,`b`,`c`,`d`.

#### Specify only a remote region

```
{
  "instances": 5,
  "constraints": [
    ["@region", "IS", "aws-us-east1"]
  ]
}
```

- No instance will launch in the local region.
- All of the 5 instances will be launched in the `aws-us-east1` region.

#### Balanced Placement for a Single Region <!-- add to constraints page , @hostname-->

```
{
   ...
  "instances": 6,
  "constraints": [
    ["@region", "IS", "aws-us-east1"],
    ["@zone", "GROUP_BY", "4"]
  ]
}
```

- Instances will all be launched in the `aws-us-east1` region and evenly divided between `aws-us-east1`‘s zones `a`,`b`,`c`,`d`.

### Increase Cluster Capacity

To increase capacity, [add new agents](1.11/administering-clusters/add-a-node/) to a remote region or regions of your cluster, and then update your services to launch instances in that region or those regions appropriately.

**Note:** You cannot configure your service to run in more than one region.
