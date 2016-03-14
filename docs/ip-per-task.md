---
title: IP-per-task
---

# IP-per-task

<div class="alert alert-danger" role="alert">
  <span class="glyphicon glyphicon-exclamation-sign" aria-hidden="true"></span> Available in Marathon Version 0.14.0+ <br/>
    The IP-per-task functionality is considered experimental, so use this
    feature at your own risk. We might add, change, or delete any
    functionality described in this document, including the API. We
    appreciate <a href="https://github.com/mesosphere/marathon/issues/2709">your feedback</a> !
</div>


In version 0.14, Marathon introduced experimental IP-per-task support. With the appropriate configuration,
every tasks of an app gets its own network interface and IP address.

This can drastically simplify service discovery, since you can use
[mesos-dns](https://github.com/mesosphere/mesos-dns) to rely on DNS for service discovery. This enables your
applications to use address-only (A) DNS records in combination with known ports to connect to other
services -- as you would do it in a traditional static cluster environment.

You can request an IP-per-task with default settings like this:

```javascript
{
  "id": "/i-have-my-own-ip",
  // ... more settings ...
  "ipAddress": {}
}
```

Marathon passes down the request for the IP to Mesos. You have to make sure that you installed & configured
the appropriate
[Network Isolation Modules](https://docs.google.com/document/d/17mXtAmdAXcNBwp_JfrxmZcQrs7EO6ancSbejrqjLQ0g) &
IP Access Manager (IPAM) modules in Mesos. The Marathon support for this feature requires Mesos v0.26.

If an application requires IP-per-task, then it can not request ports to be allocated in the slave.

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

## Service Discovery

If your application requires IP-per-task, then it can not request ports to be allocated in the slave. It is
however still possible to describe the ports that the Application's tasks expose:


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

Marathon will pass down this information to Mesos (inside the DiscoveryInfo message) when starting new tasks,
[mesos-dns](https://github.com/mesosphere/mesos-dns) will then expose this information through IN SRV records.
