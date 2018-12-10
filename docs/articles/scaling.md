# Scaling Services

## How Can I Scale a Service?
[⇒Services](services.md) in Marathon are scaled by adjusting the target instance count in the respective service definition. Adjusting the number of instances will initiate a [⇒deployment](deployments.md) and eventually create new [⇒Instances](instances.md), or tear down those that are not needed any longer. Learn more about the [⇒deployment lifecycle](deployments.md#lifecycle) and how you can be notified of the progress of a deployment.

You can scale to any integer number, or zero. If you scale to zero, all instances associated with that service will be stopped, but the service will remain so you can scale up again later. If you specify a number larger than the current count, Marathon will create additional instances, wait for Mesos offers, and eventually launch Tasks once it has received suitable offers. Learn more about [⇒Instance Life Cycle](instance-lifecycle.md) to understand what the current state of an instance implies.

Note that each change to an App or Pod will create a new version of that service, see [⇒Services Versioning](services.md#versioning) for more information on versions.

### Scaling Apps
Apps are scaled by updating the target instance count in an app definition to the required amount. The suggested way to do this is to use a `PATCH` request. The following example will update the instances for `my-app` to 2:
```
http PATCH :8080/v2/apps/my-app instances :+ 2
```

### Scaling Pods
Pods are scaled by updating the target instance count in a pod definition to the required amount. The suggested way to do this is to use a `PATCH` request. The following example will update the instances for `my-pod` to 2:
```
http PATCH :8080/v2/pods/my-pod {"scaling": { "kind": "fixed", "instances": 1 }}
```

### Auto-scaling Services
Marathon does currently not provide native means to auto scale services. There are documented concepts that can be used to implement auto-scaling, but those are not covered by automated tests and are therefore not officially supported. 

## Scaling Up
Starting with Marathon 1.8, instances will be created as soon as they are requested. However, launching actual tasks for them is dependent on suitable offers from Mesos. Learn about the [⇒Instance Life Cycle](instance-lifecycle.md) to understand the state of an instance as presented in the API.

## Scaling Down
When scaling down, Marathon will determine a set of instances to be torn down in order to meet the new requirement. You can influence the decision which instances will be decommissioned by configuring a [⇒Kill Selection](kill-selection.md) for your service. Instances of a stateless service that are determined to be torn down will be adjusted to a [⇒goal](instances.md#goals) `Decommissioned`. Instances of a stateful service will be adjusted to a goal `Stopped` instead. The main difference between these different goals is that instances with goal `Stopped` will remain in state and can be started again later.

The deployment initiated by a scale request will only finish once all selected instances have entered a terminal state. When scaling down stateful services, Marathon will keep terminal instances, as they contain information about reservations and persistent volumes. If you were to scale up again later, Marathon would pick up those existing reservations and volumes, allowing new tasks to re-use them.

If you want to explicitly get rid of a stateful instance that contains a reference to reservations and persistent volumes, you can use the `wipe` functionality of either apps or pods endpoints, respectively. If you want to enforce replacement of an unreachable instance that remains in state even though you can assess the host is gone, you can use that same `wipe` functionality:
```

```
Learn more on [Unreachable Instances](unreachable-instances.md). 

## Which related concepts should I understand?

## Links
* [API](api.md)
* [Applications](applications.md)
* [Autoscaling Services](link-to-dcos-docs)
* [Pods](pods.md)  
* [Stateful Services](stateful-services.md)
* [Unreachable Instances](unreachable-instances.md)
