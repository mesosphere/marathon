---
title: Application Deployments
---

# Application Deployments

Every change in the definition of applications or groups in Marathon is performed as a deployment.
A deployment is a set of actions, that can:

- Start/Stop one or more applications.
- Upgrade one or more applications.
- Scale one or more applications.

Deployments take time and are not instantly available. A deployment is considered active in Marathon until it has finished successfully. Marathon removes deployments after they have completed successfully.

You can perform multiple deployments at the same time as long as one application is changed only by one deployment.
If you request a deployment that tries to change an application that is already being changed by another active deployment, Marathon will reject the new deployment.

## Dependencies

Applications without dependencies can be deployed in any order without restriction.
If there are dependencies between applications, then the deployment actions will be performed in the order the dependencies require.

<p class="text-center">
  <img src="{{ site.baseurl}}/img/dependency.png" width="645" height="241" alt="">
</p>

In the example above, the application _app_ is dependent on the application _db_.

- __Starting :__ If _db_ and _app_ are added to the system, _db_ is started first and then _app_.
- __Stopping :__ If _db_ and _app_ are removed from the system, _app_ is removed first and then _db_.
- __Upgrade :__ See the Rolling Restart section. 
- __Scaling :__ If _db_ and _app_ get scaled, _db_ is scaled first and then _app_.

## Rolling Restarts

Marathon allows you to perform rolling restarts to deploy new versions of applications. In general, there are two phases to deploying a new version of an application: starting a set of processes with the new version and stopping the set of processes with the old version.

In Marathon, you can perform a rolling restart by defining an upgrade strategy with a `minimumHealthCapacity` at the application level.

The minimumHealthCapacity is a percentage which, when applied to the instance count, defines the number of healthy instances
that a certain version of the application must have at all times during update.  

- __`minimumHealthCapacity` == 0__ : All old instances can be killed before the new version is deployed.
- __`minimumHealthCapacity` == 1__ : All instances of the new version are deployed side by side before the old version is stopped.
- __`minimumHealthCapacity` between 0 and 1__ : Scale the old version to minimumHealthCapacity and start the new version to minimumHealthCapacity side by side. If this is completed successfully, the new version is scaled to 100% and the old version is stopped. 

This gets a bit more complex if there are dependencies.
In the example above, when the applications are updated, Marathon performs the following actions:
  
1. Upgrade application _db_ until all instances are replaced, ready, and healthy (taking `upgradeStrategy` into account).
1. Upgrade application _app_ until all instances are replaced, ready, and healthy (taking `upgradeStrategy` into account).

**Note:** Your cluster needs to have more capacity available for the update process if you choose a `minimumHealthCapacity` greater than 0.5. When `minimumHealthCapacity` is greater than 0.5, more than half of the instances of the same application are run side by side.
These capacity constraints are added together if there are dependencies. In our example, we defined 0.6 for _db_ and 0.8 for _app_. 
This means that when an update is performed there will be 12 instances of _db_ (6 old and 6 new) and 32 instances of _app_ (16 old and 16 new) running side by side.

## Force a Deployment

An application can be changed by only one deployment at a time.
Other changes to the application must wait until the first deployment has finished.
You can break this rule by running a deployment with the force flag.
The REST interface allows the force flag for all state-changing operations.

__ATTENTION__: The force flag should be used only in the case of a failed deployment!

If a force flag is set, all deployments that are affected by this deployment are cancelled.
This action may leave the system in an inconsistent state. Specifically, if a deployment is cancelled when an app is in the middle
of a rolling upgrade, it can end up in a state where some old and
some new tasks are running. If the new deployment does not update that app, it will stay in
that state until a future deployment is made for that app.

The only kind of deployments that can be force-updated safely are those that
affect single apps only. The only good reason to force a deployment that affects multiple apps is to correct
a failed deployment.

## A failed Deployment

A deployment consists of steps executed one after the other.
The next step is only executed if the previous step has finished successfully.

There are circumstances in which a step will never finish successfully. For example:

- The new application does not start correctly.
- The new application does not become healthy.
- A dependency of the new application was not declared and is not available.
- The capacity of the cluster is exhausted.
- The app uses a Docker container and the configuration changes at [Running Docker Containers on Marathon](https://mesosphere.github.io/marathon/docs/native-docker.html) were not made.

The deployment in these cases would take forever.
To heal the system, a new deployment must be applied to correct the problem with the current deployment.

## The /v2/deployments endpoint

The list of running deployments can be accessed via the [/v2/deployments](rest-api.html#deployments) endpoint.
There are several items of information available for every deployment:

- `affectedApps`: Which applications are affected by this deployment.
- `steps`: The steps to perform for this deployment.
- `currentStep`: Which step is currently being performed.
 
Every step can have several actions. The actions inside a step are performed concurrently.
Possible actions are:

- `StartApplication`: Start the specified application.
- `StopApplication`: Stop the specified application.
- `ScaleApplication`: Scale the specified application.
- `RestartApplication`: Restart the specified application to the `minimumHealthStrategy`.
- `KillAllOldTasksOf`: Kill the rest of tasks of the specified application.






