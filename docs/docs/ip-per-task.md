---
title: IP-per-task
---

# IP-per-task

<div class="alert alert-danger" role="alert">
  <span class="glyphicon glyphicon-exclamation-sign" aria-hidden="true"></span> Available in Marathon Version 0.14.0+ <br/>
    IP-per-task functionality is experimental, so use this feature at your own risk. We might add, change, or delete any functionality described in this document, including the API. We appreciate <a href="https://github.com/mesosphere/marathon/issues/2709">your feedback</a> !
</div>


In version 0.14, Marathon introduced experimental IP-per-task support. With the appropriate configuration, every tasks of an app gets its own network interface and IP address.

This drastically simplifies service discovery, since you can use
[Mesos-DNS](https://github.com/mesosphere/mesos-dns). Mesos-DNS enables your
applications to use address-only (A) DNS records in combination with known ports to connect to other services, as you would do it in a traditional static cluster environment.

You can request an IP-per-task with default settings like this:

```javascript
{
  "id": "/i-have-my-own-ip",
  // ... more settings ...
  "ipAddress": {}
}
```

## Prerequisite Mesos Configuration

Marathon passes the request for the IP to Mesos, which has to be configured correctly in order for IP-per-task to function.

- You must be running Mesos v0.26.

- You must install and configure the appropriate [Network Isolation Modules](https://docs.google.com/document/d/17mXtAmdAXcNBwp_JfrxmZcQrs7EO6ancSbejrqjLQ0g) and IP Access Manager (IPAM) modules in Mesos.

## Network security groups

If your IP Access Manager (IPAM) supports it, you can refine your IP configuration using network security groups and labels:

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

Network security groups only allow network traffic between tasks that have at least one of their configured groups in common. This makes it easy to prevent your staging environment from interfering with production traffic.

## Service Discovery

If your application requires IP-per-task, then it can not request ports to be allocated in the slave. However, it is still possible to describe the ports that the application's tasks expose:


```javascript
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

Marathon will pass down this information to Mesos (inside the DiscoveryInfo message) when it starts new tasks. [Mesos-DNS](https://github.com/mesosphere/mesos-dns) will then expose this information through IN SRV records.

In the future, Marathon will also fill in the DiscoveryInfo message for applications that do not require IP-per-task.

## Limitations

- If an application requires IP-per-task, then it can not request ports to be allocated in the slave.

- Currently, this feature does not work in combination with Docker containers.
