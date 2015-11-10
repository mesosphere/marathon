---
title: Application Deployments
---

# Application Deployments

Every change in the definition of applications or groups in Marathon is performed as a deployment.
A deployment is a set of actions, that can do:

- Start/Stop one or more applications
- Upgrade one or more applications
- Scale one or more applications

Deployments are not instantly available - they take time. 
A deployment is active in Marathon until the deployment is finished successfully.

Multiple deployments can be performed at the same time, as long as one application is changed only by one deployment.
If a deployment is requested, that tries to change an application that is already being changed by another active deployment, 
the new deployment request will be rejected.

## Dependencies

Applications without dependencies can be deployed in any order without restriction.
If there are dependencies between applications, then the deployment actions will be performed in a specific order.

<p class="text-center">
  <img src="{{ site.baseurl}}/img/dependency.png" width="645" height="241" alt="">
</p>

In the example above the application _app_ is dependent on the application _db_.

- __Starting :__ if _db_ and _app_ is added to the system, _db_ is started first and then _app_
- __Stopping :__ if _db_ and _app_ is removed from the system, _app_ is removed first and then _db_
- __Upgrade :__ See section Rolling Restart. 
- __Scaling :__ if _db_ and _app_ get scaled, _db_ is scaled first and then _app_

## Rolling Restarts

One of the most common problems facing developers and operators is how to roll out new versions of applications. 
At the root, this process consists of two phases: starting a set of processes with the new version and stopping the set of processes with the old version.
There are multitude of possible models how this process can be performed. 

In Marathon there is an upgrade strategy with a minimumHealthCapacity, which is defined on application level.
The minimumHealthCapacity is a percentage which, when applied to the instance count, defines the number of healthy instances
that a certain version of the application must have at all times during update.  

- __minimumHealthCapacity == 0__ : all old instances can be killed, before the new version is deployed.
- __minimumHealthCapacity == 1__ : all instances of the new version is deployed side by side, before the old version is stopped 
- __minimumHealthCapacity between 0 and 1__ : scale old version to minimumHealthCapacity and start the new version to minimumHealthCapacity side by side. If this is completed successfully then the new version is scaled to 100% and the old version is stopped. 

This gets a little bit more complex if there are dependencies.
When the applications of the example above are updated, the following actions will be performed:
  
1. Scale old application db to instance count 6
2. Start new application of db to instance count 6
3. Scale old application app to instance count 16
4. Start new application of app to instance count 16
5. Stop all instances of old app
6. Stop all instances of old db
7. Scale new db to instance count to 10
8. Scale new application of app to instance count 20

Please take into account that your cluster needs to have more capacity available for the update process if you choose a minimumHealthCapacity greater 0.5.
In this case more than half of the instances of the same application are run side by side.
These capacity constraints are summed up if there are dependencies. In our example, we defined 0.6 for db and 0.8 for app. 
This means that in the update case, we have 12 instances of db (6 old and 6 new) and 32 instances of app (16 old and 16 new) running side by side.

## Force a Deployment

An application can be changed by only one deployment at a time.
Other changes to the application must wait until the first deployment has finished.
It is possible to break this rule by running a deployment with the force flag.
The REST interface allows the force flag for all state-changing operations.

__ATTENTION__: The force flag should be used only in the case of a failed deployment!

If a force flag is set, then all deployments that are affected by this deployment are cancelled.
This may leave the system in an inconsistent state. Specifically, when an app is in the middle
of a rolling upgrade and the deployment is cancelled, it may end up in a state where some old and
some new tasks are running. If the new deployment does not update that app, it will stay in
that state until a future deployment is being made for that app.

By contrast, the only kind of deployments that can be force-updated safely are those which
affect single apps only.

Consequently, the only good reason to force a deployment affecting multiple apps is to correct
a failed deployment.


## A failed Deployment

A deployment consists of steps. The steps are executed one after the other.
The next step is only executed, if the previous step has finished successfully.

There are circumstances where a step will never finish successfully. For example:

- the new application does not start correctly
- the new application does not become healthy
- a dependency of the new application was not declared and is not available
- the capacity of the cluster is exhausted  
- the app uses a Docker container and the changes listed at [Running Docker Containers on Marathon]
(https://mesosphere.github.io/marathon/docs/native-docker.html) were not followed
- ...

The deployment in this case would take forever.
To heal the system, a new deployment must be applied to correct the problem with the current deployment.

## The /v2/deployments endpoint

The list of running deployments can be accessed via the [/v2/deployments](rest-api.html#deployments) endpoint.
There are several items of information available for every deployment:

- affectedApps: which applications are affected by this deployment
- steps: the steps to perform for this deployment
- currentStep: which step is actually performed 
 
Every step can have several actions. The actions inside a step are performed concurrently.
Possible actions are:

- __ResolveArtifacts__ Resolve all artifacts of the application and persist it in the artifact store
- __StartApplication__ Start the specified application 
- __StopApplication__ Stop the specified application 
- __ScaleApplication__ Scale the specified application 
- __RestartApplication__ Restart the specified application to the minimumHealthStrategy 
- __KillAllOldTasksOf__ Kill the rest of tasks of the specified application 






