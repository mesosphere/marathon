---
title: Slow Docker apps and deployments
---
# You experience slow Docker apps and deployments?

## The problem
When using Apache Mesos you have the choice if you want to use CPU shares or strict CPU limitations. If you are using CPU shares, your task can consume more CPU cycles than initially configured in your Marathon app definition, if your host system has free CPU cycles. If you are using strict CPU limitations, your task can only consume a maximum of CPU time based on your marathon configuration. The default configuration for Mesos is to use CPU shares. CFS strict CPU limitation as default were introduced in DC/OS a while ago, but until recently this configuration are only respected by the mesos executor and not by the docker executor. This [issue](https://issues.apache.org/jira/browse/MESOS-6134) was fixed in the latest Mesos release, which is also included in DC/OS 1.10.

If you recently upgraded to DC/OS 1.10 or configured `MESOS_CGROUPS_ENABLE_CFS=true` in your Mesos agent configuration and you are now seeing slow running Docker applications or slow deployments, you probably want to take action!

## Solving the issue
Basically you have two options to solve the described issue.

### Increase the required CPU amount in your marathon app definition.
Your apps/deployments are running slow, because they require more CPU cycles than they are able to consum. Therefore the easiest way to solve this issue is to change the resource requirements in your marathon app definition. Just change the `”cpus”` property of your app definition to a higher value and test if this change solves your issues. 

### Change Mesos Agent configuration to not use strict CFS CPU limitations.
Maybe the majority of your applications have a CPU peak during startup and a lower consumption afterwards or you have other advanced CPU loads. If you do not want strict CPU separation, you are able to change the current default behavior.

In this scenario you need to change the configurations for your DC/OS installation as well as your Mesos Agent configurations. You need to change this line your [dcos-config.yaml]( https://github.com/dcos/dcos/blob/a7a30779663081198649caecb4d27165836e73ae/gen/dcos-config.yaml#L431) to `MESOS_CGROUPS_ENABLE_CFS=false`. Now you could perform either a DC/OS re-installation or ssh to all Mesos Agent nodes, change the configuration in `/opt/mesosphere/etc/mesos-slave-common` to `MESOS_CGROUPS_ENABLE_CFS=false` and restart the Mesos agent process with `sudo systemctl restart dcos-mesos-slave`.

If you are considering changing this configuration, you should also have a look at the [Mesos oversubscription](http://mesos.apache.org/documentation/latest/oversubscription/) feature.