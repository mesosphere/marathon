---
title: IP-per-task
---

# IP-per-task

<div class="alert alert-danger" role="alert">
  <span class="glyphicon glyphicon-exclamation-sign" aria-hidden="true"></span> Available in Marathon Version 0.14.0+ <br/>
    The IP-per-task API has been deprecated in favor of the [Networking API](networking.html).
    Use this deprecated API at your own risk. It will be removed in a future Marathon release.
    We appreciate <a href="https://jira.mesosphere.com/browse/MARATHON-3429">your feedback</a>!
</div>


In version 0.14, Marathon introduced experimental IP-per-task support. With the appropriate configuration,
each of an app's tasks gets its own network interface and IP address.

IP-per-task drastically simplifies service discovery, since you can use
[Mesos-DNS](https://github.com/mesosphere/mesos-dns) to rely on DNS for service discovery. Mesos-DNS enables your
applications to use address-only (A) DNS records in combination with known ports to connect to other
services, as you would do it in a traditional static cluster environment.

Request an IP-per-task with default settings by adding the following to your application definition:

```json
{
  "id": "/i-have-my-own-ip",
  // ... more settings ...
  "ipAddress": {}
}
```

Marathon passes down the request for the IP to Mesos. You have to make sure that you installed and configured
the appropriate
[Network Isolation Modules](https://docs.google.com/document/d/17mXtAmdAXcNBwp_JfrxmZcQrs7EO6ancSbejrqjLQ0g) and
IP Access Manager (IPAM) modules in Mesos. Marathon support for this feature requires Mesos v0.26.

If an application requires IP-per-task, it cannot request ports to be allocated in the agent node.

Currently, this feature does not work in combination with Docker containers. We might still change some
aspects of the API and we appreciate your feedback.

## Network security groups

If your IP Access Manager (IPAM) supports it, you can refine your IP configuration using network security
groups and labels:

```javascript
{
  "id": "/i-have-my-own-ip",
  // ... more settings ...
  "ipAddress": {
    "groups": ["production"],
    "labels": {
      "some-meaningful-config": "potentially interpreted by the IPAM"
    }
  }
}
```

Network security groups only allow network traffic between tasks that have at least one of their configured
groups in common. This makes it easy to disallow your staging environment to interfere with production
traffic.

## Named Networks

If your IPAM supports it, you can instruct Mesos to associate your application's containers with a specific named network:

```javascript
{
  "id": "/task-assigned-to-named-network",
  ...
  "ipAddress": {
    "networkName": "devops"
  }
}
```

Applications that specify a named network are automatically assigned an IP address, for each task, by the currently configured IPAM.
Named network assignment is influenced by the `--default_network_name` flag: if a default network name has been configured then:

1. An application that specifies an `ipAddress` but not its `networkName` field will implicity use the network named by `--default_network_name`.
2. An application that specifies an `ipAddress.networkName` value overrides any value of `--default_network_name`.
3. If an application MUST use the **physical host network** then the `ipAddress` field MUST be omitted from the application definition.

Marathon passes the network name through to Mesos and is the responsibility of Mesos (and/or the loaded IPAM modules) to validate the network name.

## Service Discovery

While an application that requires IP-per-task cannot request that ports be allocated in the agent node, you can still specify the ports that the application's tasks expose:


```json
{
  "id": "/i-have-my-own-ip",
  // ... more settings ...
  "ipAddress": {
    "discovery": {
      "ports": [
        { "number": 80, "name": "http", "protocol": "tcp" }
      ]
    }
      // ... more settings ...
  }
}
```

Marathon will pass this information to Mesos (inside the DiscoveryInfo message) when starting new tasks. [Mesos-DNS](https://github.com/mesosphere/mesos-dns) will then expose this information through IN SRV records.
