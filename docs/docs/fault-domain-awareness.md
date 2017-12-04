---
title: Fault Domain Awareness
---

# Fault Domain Awareness

## Overview

A fault domain is a set of nodes that share similar failure and latency characteristics. Two nodes in the same fault domain are both affected by failure events within the domain. Placing nodes in more than one fault domain reduces the risk that a failure will affect both nodes.

Marathon supports fault domain awareness, so that you can mitigate the risk of fault domain failure for high availability and configure your apps and pods to take advantage of region awareness to allow for increased capacity when needed.

Marathon currently offers hierarchical 2-level fault domain: zone and region.
	
## Zone fault domains
Zone fault domains offer a moderate degree of fault isolation because they share the same region. However, network latency between zones in the same region is moderately low (typically < 10ms).
	
For on-premise deployments, a zone would be a physical data center rack. 

For public cloud deployments, a zone would be the "availability zone” concept provided by most cloud providers.
	
Spread your apps or pods across zones if you are latency-sensitive or if you need a moderate degree of high availability: you can place your apps in one region, but balance across zones.

## Region fault domains

Region fault domains offer the most fault isolation, though inter-region network latency is high (typically 50-100ms ). 
	 
For on-premise deployments, a region might be a data center.
	 
For public cloud deployments, most cloud providers expose a “region” concept.
	 
You can deploy your apps or pods in a specific region region based on the available capacity.

### Local and remote regions

- A **local region** is a region that contains the master nodes.
- A **remote region** contains only agent nodes. There is high latency between a remote region and the local region.

## Mesos installation considerations

- Masters nodes must be in the same region because otherwise the latency between them will be too high. They should, however, be spread across zones for fault-tolerance.
	
- You must have less than 100 ms latency between regions.

## Adding fault domain awareness to your app or pod definition

In your Marathon app or pod definition, you can use placement constraints to:

- Specify a region and zone for your app or pod, so that all instances of the app or pod will be scheduled only in that region and zone.

- Specify a region without a specific zone, so that all instances of a given app or pod will be scheduled in that region (but not necessarily in the same zone).

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

#### Balanced Placement for a Single Region

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

### Use Case: Use Remote Regions to Increase Capacity

You can configure apps and pods to use remote regions when extra capacity is needed. Your cluster must consist of one local region, which contains the master agents, system services, and agents, and one or more remote regions, which will contain only agents.

To increase capacity, you will add new agents to a remote region or regions of your cluster, and then update your apps or pods to be launched in that region or those regions appropriately.
