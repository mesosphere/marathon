---
title: Slow Docker Apps and Deployments
---

# Slow Docker apps and deployments

If you recently upgraded to DC/OS 1.10 or configured `MESOS_CGROUPS_ENABLE_CFS=true` in your Mesos agent configuration, you may see slow-running Docker applications or slow deployments.

## Strict CPU limitations are now default

When using Apache Mesos, you can choose to use either CPU shares or strict CPU limitations. If you use CPU shares, your task can consume more CPU cycles than initially configured in your Marathon app definition, if your host system has free CPU cycles. If you are using strict CPU limitations, your task can only consume a maximum of CPU time based on your Marathon configuration.

The default Mesos configuration uses CPU shares. CFS (Completely Fair Scheduler) strict CPU limitation was introduced as a default in DC/OS a while ago, but until recently this configuration is only respected by the Mesos executor and not by the Docker executor. [MESOS-6134](https://issues.apache.org/jira/browse/MESOS-6134) was fixed in the latest Mesos release, which is also included in DC/OS 1.10.

Your apps or deployments are likely running slowly because they require more CPU cycles than they are configured to consume.

## Steps to take

### Increase CPU allocation

If you have slow-running Docker applications or deployments due to DC/OS upgrade or configuring `MESOS_CGROUPS_ENABLE_CFS=true`, increase the required CPU amount in your Marathon app definition. Change the `"cpus"` property of your app definition to a higher value and test if this change solves your issues. 

### Change Mesos agent configuration

In special cases, you may want to change Mesos agent configuration to not use strict CFS CPU limitations. Consider this if the majority of your applications have a CPU peak during startup and a lower consumption afterwards, or you have other advanced CPU loads. You should only change the default behavior if you do not need strict CPU separation.

You will need to change the configurations for your DC/OS installation (if you are running DC/OS) or change your Mesos agent configurations. If you are not using DC/OS, you will need to change your Mesos agent configurations.

**Note:** If you are considering changing this configuration, consult the [Mesos oversubscription](http://mesos.apache.org/documentation/latest/oversubscription/) documentation for additional considerations and configuration options.

#### DC/OS users

1. Change this line in your [dcos-config.yaml]( https://github.com/dcos/dcos/blob/a7a30779663081198649caecb4d27165836e73ae/gen/dcos-config.yaml#L431) to `MESOS_CGROUPS_ENABLE_CFS=false`.

1. [Reinstall DC/OS](https://dcos.io/docs/1.10/installing/).

1. Alternatively, you can follow the instructions for non-DC/OS users, below.

#### Non-DC/OS users

1. SSH to each Mesos agent node.

1. On each node, change the configuration in `/opt/mesosphere/etc/mesos-slave-common` to `MESOS_CGROUPS_ENABLE_CFS=false`

1. Restart the Mesos agent process with `sudo systemctl restart dcos-mesos-slave`.
