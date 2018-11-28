# Instances

## What is an Instance?
Instances are the Marathon representation of [⇒Tasks](tasks.md) in Mesos. An Instance can exist even if there is currently no Task associated. Depending on the goal of a given Instance, Marathon will take actions to reach that goal: it might match offers in order to launch a Task or [⇒TaskGroup](taskGroups.md), or it might send kill requests to Mesos and eventually expunge the Instance from its state. See [⇒Instance Life Cycle](instanceLifeCycle.md) for more information.

## How can I use Instances?
Instances are created based on [⇒Service](services.md) specifications which can be either an [⇒App](apps.md) or a [⇒Pod](pods.md). Instances are implicitly created according to their App or Pod definition, which contains an instances property that designates the required amount of instances.

## Which related concepts should I understand?
When upgrading a Service during a [⇒Deployment](deployments.md) from one version to another, Marathon can create more instances than specified in either Service definition. It will do so if allowed via the [⇒upgradeStrategy](upgradeStrategy.md) configuration.
When an instance is running on an agent disconnected from the cluster, it will eventually be reported as unreachable. According to the Service’s [⇒unreachableStrategy](unreachableStrategy.md), Marathon can schedule a replacement, even though the unreachable instance might still be running. When an unreachable instance becomes becomes reachable again, Marathon will scale down accordingly to the required amount of instances. It will decide over which instance shall be killed based on the [⇒killSelection](killSelection.md).

## How should I use Instances?
_(This section could point out how to use Service definitions so that the user doesn’t need one app per instance, thus circumventing known limitations.)_

## Are there any limitations or things to consider?
_(This section could point out known limitations wrt number of instances.)_

## Links
* [⇒Scaling Applications](apps-scaling.md)
* [⇒Scaling Pods](pods-scaling.md)
* [⇒Killing Instances](instance-operations.md#delete-instances)

## Examples
* [⇒Simple Application](examples/app-simple.json) definition configuration 3 instances
* [⇒JSON Representation](examples/app-representation.json) of an Instance via v2/tasks
