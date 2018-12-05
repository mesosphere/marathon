# Deployments

# What is a deployment
Every time a user creates a new [service](services.md) modifies or removes an existing one a deployment is created. Basically, deployment is a series of steps that Marathon has to execute in order to fulfill the user request. Let's consider an example where a few services are already deployed and running:
```
/product
   /database
     /mysql
     /mongo
   /service
     /play-app
```
Now, a new service `/product/service/rails-app` needs to be deployed. To achieve this a user submits service definition to the `/v2/apps` [Rest API](api.md) endpoint:
```
{
  "id": "/product/service/rails-app",
  "cpus": 0.3,
  "instances": 1,
  "mem": 2048,
  "cmd": ...
}
```
If the passed definition is valid and there are no [deployment conflicts](deployments.md#forced-deployments) Marathon will return a `HTTP 201 (created)` response with a normalized service definition (including default fields that are allowed to be omitted) with an extra `deployments` field containing the deployment Id:
```
{
  "id": "/product/service/rails-app",
  ...
  "deployments":[{
      "id": "3bcb15fc-737f-4a36-b185-2ae3a703239e"
    }
  ],
```
This deployment Id is a unique identifier for the process of, well deploying the application. During a deployment Marathon will:

- wait for Mesos to offer resources
- find one with fitting resources (given resources and other constraints)
- launch a Mesos [task](tasks.md)
- wait for the task to be up and running
- should any health checks be defined, wait for them to become green

Afterward, the deployment is considered finished and is removed from Marathon state. Now, given enough resources in the cluster, no special resource [constraints](constraints.md) (e.g. service should be launched on a specific agent) launching a task usually takes on the order of a few seconds. However, sometimes it can take a while to launch a service. Typical reasons for that might be:

- Not enough available cluster resources
- Resources are available but specific constraints have to be fulfilled (e.g specific agent)
- Tasks fail repeatedly due to a failure in the environment or configuration
- Tasks start successfully but defined health check does not turn green for some reason
- etc. For more information on why a deployment may not finish successfully see [troubleshooting](troubleshooting.md)

## Deployment status
In all the above cases Marathon will keep retrying to deploy the task indefinitely. During the deployment lifetime its status can be queried from the `/v2/deployments` [Rest API](api.md) endpoint. Let's change the above `/product/service/rails-app` definition and have it request 1000 CPUs using `"cpus": 1000` (it's a rails app after all so those CPUs might be actually needed ^.^). Here, Marathon will have difficulties finding an agent with enough CPUs so that we have enough time to examine the deployment using `GET /v2/deployments` request. The result looks like:
```
[
    {
        "id": "3bcb15fc-737f-4a36-b185-2ae3a703239e",
        "affectedApps": [
            "/product/service/rails-app"
        ],
        "affectedPods": [],
        "steps": [
            {
                "actions": [{
                        "action": "StartApplication",
                        "app": "/product/service/rails-app"
                    }
                ]
            },
            {
                "actions": [{
                        "action": "ScaleApplication",
                        "app": "/product/service/rails-app"
                    }
                ]
            }
        ],
        "currentActions": [
            {
                "action": "ScaleApplication",
                "app": "/product/service/rails-app"
            }
        ],
        "currentStep": 2,
        "totalSteps": 2
    }
]
```
This endpoint returns a list of currently active deployments (some fields omitted for brevity). Interesting fields are:
- `id` - this is our `deploymentId` from the original response
- `affectedApps` - a deployment may actually "affect" (add, remove or modify) multiple service definitions (e.g. in a case of a group deployment)
- `steps` - a series of actions to achieve the defined goal. This is being referred to as a [deployment plan](deployments.md#deployment-plan)
- `currentAction` - currently executed action of the plan

## Deployment plan
In the above example `steps` field holds the deployment plan. Our deployment plan has two deployment steps: `StartApplication` and `ScaleApplication`. Currently, following deployment steps exist:

- `StartApplication` - this step exists for backward compatibility reasons. Currently, it is doing nothing and is immediately successful
- `ScaleApplication` - is responsible for starting the defined number of tasks and waiting for them to be up and running
- `StopApplication` - stops given service by killing its tasks and waiting for the kill confirmation
- `RestartApplication` - used when modifying an existing service definition and restarting its tasks (using the new definition)

Steps are executed sequentially, however actions within one step (should there be more than one) are executed in parallel. Once all steps are executed successfully, the deployment is finished and removed from the state.

## Canceling a deployment
Sometimes a deployment is "stuck" for some reason (see [troubleshooting](troubleshooting.md) for more information). In this case, it might be helpful to cancel the deployment, returning to the previous state. Let's cancel the above stuck deployment by sending a `DELETE /v2/deployments/3bcb15fc-737f-4a36-b185-2ae3a703239e` request. The response is `HTTP 200(ok)` but the payload might be somewhat a surprise:
```
{
    "version": "2018-12-05T00:28:00.584Z",
    "deploymentId": "342a6dc5-f871-4d10-a4ec-bbe21b431d58"
}
```
We see that another `deploymentId` is returned. This is because internally canceling a deployment simply creats another deployment that reverts the steps of the original one.

**Known caveats:** Marathon has a cap on the number of concurrently running deployments. This is controlled by `--max_running_deployments` [command line argument](command-line-arguments.md). In some rare occasions, one can have the maximum number of running deployments achieved so that canceling one is not possible (since a new deployment has to be created). In this case, canceling using `?force=true` parameter aka. [forced deployment](deployments.md#forced-deployments) should be used.

## Forced deployments
Let's get back to our `product/service/rails-app` service deployment that is now "stuck" with slightly exaggerated resource requirements. We realize that 1k CPUs is too much and decide to update the `product/service/rails-app` service definition with reasonable CPU requirements. However, when submitting new service definition, Marathon will respond with `HTTP 209 (conflict)`:
```
{
    "message": "App is locked by one or more deployments. Override with the option '?force=true'",
    "deployments": [
        {
            "id": "3aa7316a-7888-427b-9af3-4179a7791c79"
        }
    ]
}
```
This is due to already existing (stuck) deployment that affects our service definition. In general, Marathon will only allow one deployment at a time affecting any service definition (or service definition group). Let's try to submit our corrected definition again, however, this time using `?force=true` parameter (`POST /v2/apps?force=true`). This time, the result is `HTTP 200(ok)` and we receive a new deployment Id back. Under the hood, Marathon will cancel the previous deployment and start the new one. The new deployment will reconcile existing tasks (if any), check their service definition version, kill old ones if necessary and start new tasks.

**Known caveats:** Due to its "destructive potential" usage of forced deployments is discouraged. A forced deployment will cancel any existing conflicting deployments (there might be more than one, see [group deployments](deployments.md#group-deployments)) potentially killing more tasks than the user intended.

## Stopping a deployment
In some rare cases a deployment might be stuck and canceling it is not an option (maybe because some tasks were already partially started and the user doesn't want to kill them). In this case, a deployment might be stopped. Stopping a deployment (as opposed to canceling a deployment) **will not return Marathon to the previous state**. Whichever tasks were already started will continue to run, the rest will not be launched. This operation will leave the service in an inconsistent state e.g. only 3/5 running tasks or 3 tasks running with the newest service definition version and 2 with the old one. **Stopping deployments should be a measure of last resort. Its usage is strongly discouraged**.

# Group deployments
Let's assume that we have already deployed databases in our cluster and now it's time to deploy the services:
```
/database
    /mysql
    /mongo
```
Note that we got rid of the top level `/product` group (the reason for this in the caveats section below).

Should we have a lot of services (and big products might have hundreds or even thousands micro-services) we can deploy all of them using one request. Let's `POST` following service group definition to `/v2/groups`:
```
{
    "id": "service",
    "apps": [
    {
        "id": "play-app",
        "cmd": "sleep 100000",
        "cpus": 0.03,
        "mem": 32,
        "disk": 0,
        "instances": 1
    },
    {
        "id": "rails-app",
        "cmd": "sleep 100000",
        "cpus": 0.03,
        "mem": 32,
        "disk": 0,
        "instances": 1
    }
    ]
}
```
(simple `sleep`s will be started). Marathon responds with usual `HTTP 201(Created)` and a `deploymentId`. Requesting the deployment status from `/v2/deployments` returns:
```
{
    "affectedApps": [
        "/service/play-app",
        "/service/rails-app"
    ],
    "steps": [
        {
            "actions": [
                {
                    "action": "StartApplication",
                    "app": "/service/play-app"
                },
                {
                    "action": "StartApplication",
                    "app": "/service/rails-app"
                }
            ]
        },
        {
            "actions": [
                {
                    "action": "ScaleApplication",
                    "app": "/service/play-app"
                },
                {
                    "action": "ScaleApplication",
                    "app": "/service/rails-app"
                }
            ]
        }
    ],
    "currentActions": [
        {
            "action": "ScaleApplication",
            "app": "/service/play-app",
            "readinessCheckResults": []
        },
        {
            "action": "ScaleApplication",
            "app": "/service/rails-app",
            "readinessCheckResults": []
        }
    ]
}
```
Here, we can see that we have a deployment affecting two different services (`service/play-app` and `service/rails-app`), with deployment steps changing both services. Note that Marathon will try to start both services in parallel and the deployment finishes when **all affected services** are up and running.

After the deployment is finished our service tree looks like:
```
/database
    /mysql
    /mongo
/service
    /play-app
    /rails-app
```

The same way a whole service group (including **all** transitive services) can be removed with a single `DELETE /v2/groups/service` request.

**Known caveats:** Deploying a new `/service` group definition will **completely replace** all existing services in this group. This is the reason why we removed the top-level `/product` group since otherwise, our services would've replaced the databases. Group level operations should be used carefully because of their destructive potential. However, having to issue only one request for the whole group, can be significantly faster than submitting potentially thousands of services individually. Carefully grouping your services is the key here.

## Are there any limitations or things to consider?
Caveats with regard to [stoping](deployments.md#stop-deployment) or [forcing](deployments.md#forced-deployment) a deployment are described in their corresponding sections above. Another existing limitation is that group deployments are currently restricted to [apps](apps.md). [Pods](pods.md) has to be deployed via the `/v2/pods` [Rest API](api.md) endpoint.

## Links
* [Service groups](service-groups.md)
* [Services](services.md)
* [Apps](apps.md)
* [Pods](pods.md)
* [Rest API](api.md)