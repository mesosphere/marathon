---
title: An App Requires a Specific Host Port
---

# An App Requires a Specific Host Port

Generally, it is not recommended to configure your apps to depend on a particular host port because it constraints
scheduling. We know that it is sometimes only avoidable at great cost so Marathon allows you to specify a required
host port by:

* ([For Docker Apps with Bridge Networking]({{ site.baseurl }}/docs/native-docker.html#bridged-networking-mode)): 
  Specifying a non-zero `"hostPort"` in your `"portBindings"`.
* Setting setting `"requirePorts": true` in your app definition.

Marathon can only
use offers for such offers that include the required host ports. By default, 
Mesos agents do NOT offer all ports, so if you require
port e.g. 80 you have to make sure that this port is offered by the agent. You can make all ports from 1 to 65000
available to the mesos agent by using `--resources=ports:[1-65000]`. Please make sure that only tasks scheduled
by Mesos use ports in the specified range! You can find the relevant configuration options for the Mesos agent 
[here](http://mesos.apache.org/documentation/attributes-resources/).