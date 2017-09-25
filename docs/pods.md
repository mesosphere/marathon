---
title: Pods
---

# Pods

As of version 1.4, Marathon supports the creation and management of pods. Pods enable you to share storage, networking, and other resources among a group of applications on a single agent, address them as one group rather than as separate applications, and manage health as a unit.

Pods allow quick, convenient coordination between applications that need to work together, for instance a primary service and a related analytics service or log scraper. Pods are particularly useful for transitioning legacy applications to a microservices-based architecture.

Currently, Marathon pods can only be created and administered via the `/v2/pods/` endpoint of the REST API, not via the web interface.

**Note:** Pods are only available as of Marathon 1.4 and are not supported in the strict security mode of [Enterprise DC/OS](https://docs.mesosphere.com) 1.9 and below.

## Features

- Co-located containers.
- Pod-level resource isolation.
- Pod-level sandbox and ephemeral volumes.
- Pod-level health checks.

## Quick Start

1. Run the following REST call, substituting your IP and port for `<ip>` and `<port>`:

        curl -X POST -H "Content-type: application/json" -d@- http://<ip>:<port>/v2/pods <<EOF
        {
           "id": "/simplepod",
           "scaling": { "kind": "fixed", "instances": 1 },
           "containers": [
             {
               "name": "sleep1",
               "exec": { "command": { "shell": "sleep 1000" } },
               "resources": { "cpus": 0.1, "mem": 32 }
             }
           ],
           "networks": [ {"mode": "host"} ]
        }
        EOF

    **Note:** The pod ID (the `id` parameter in the pod specification above) is used for all interaction with the pod once it is created.

1. Verify the status of your new pod:

        curl -X GET http://<ip>:<port>/v2/pods/simplepod::status

1. Delete your pod:

        curl -X DELETE http://<ip>:<port>/v2/pods/simplepod

## Technical Overview

A pod is a special kind of Mesos task group, and the tasks or containers in the pod are the group members.* A pod instance’s containers are launched together, atomically, via the [Mesos LAUNCH_GROUP](https://github.com/apache/mesos/blob/cfeabec58fb2a87076f0a2cf4d46cdd02510bce4/docs/executor-http-api.md#launch_group) call. Containers in pods share networking namespace and ephemeral volumes.

You configure a pod via a pod definition, which is similar to a Marathon application definition. There are some differences between pod and application definitions, however. For instance, you will need to specify an endpoint (not a port number) in order for other applications to communicate with your pod, pods have a separate REST API, and pods support only Mesos-level health checks. This document outlines how to configure and manage pods.

\* Pods cannot be modified by the `/v2/groups/` endpoint, however. Pods are modified via the `/v2/pods/` endpoint.

### Networking
Marathon pods only support the [Mesos containerizer](http://mesos.apache.org/documentation/latest/mesos-containerizer/). The Mesos containerizer supports multiple image formats, including Docker.

The Mesos containerizer simplifies networking by allowing the containers of each pod instance to share a network namespace and communicate over a VLAN or private network. If you specify a container network without a name in a pod definition, it will be assigned to the default network.

If you need other applications to communicate with your pod, specify an endpoint in your pod definition. Other applications will communicate with your pod by addressing those endpoints. See [the Examples section](#endpoints) for more information.

In your pod definition, you can declare a `host` or `container` network type. Pods created with `host` type share the network namespace of the host. Pods created with `container` type use virtual networking. If you specify the `container` network type and Marathon was not configured to have a default network name, you must also declare a virtual network name in the `name` field. See the [Examples](#examples) section for the full JSON.

### Ephemeral Storage
Containers within a pod share ephemeral storage. Volumes are declared at the pod-level and referenced by `name` when mounting them into specific containers.

### Pod Events and State

 When you update a pod that has already launched, the new version of the pod will only be available when redeployment is complete. If you query the system to learn which version is deployed before redeployment is complete, you may get the previous version as a response. The same is true for the status of a pod: if you update a pod, the change in status will not be reflected in a query until redeployment is complete.

 History is permanently tied to `pod_id`. If you delete a pod and then reuse the ID, even if the details of the pod are different, the new pod will have the previous history (such as version information).

### Pod Definitions

Pods are configured via a JSON pod definition, which is similar to an [application definition]({{ site.baseurl }}/docs/application-basics.html). You must declare the resources required by each container in the pod because Mesos, not Marathon, determines how and when to perform isolation for all resources requested by a pod. See the [Examples](#examples) section for complete pod definitions.

#### Executor Resources

The executor runs on each node to manage the pods. By default, the executor reserves 32 MB and .1 CPUs per pod for overhead. Take this overhead into account when declaring resource needs for the containers in your pod. You can modify the executor resources in the `executorResources` field of your pod definition.

```json
{
    "executorResources": {
        "cpus": 0.1,
        "mem": 64,
        "disk": 10mb
    }
}
```

#### Secrets

Specify a secret in the `secrets` field of your pod definition. The argument should be the fully qualified path to the secret in the store.

```json
{
    "secrets": {
        "someSecretName": { "source": "/fully/qualified/path" }
    }
}
```

If you are not using Marathon on DC/OS, you will also need to enable the `secrets` feature via Marathon command-line flags and have a secrets plugin implementation.

#### Volumes

Pods support ephemeral volumes, which are defined at the pod level. Your pod definition must include a `volumes` field that specifies at least the name of the volume and a `volumeMounts` field that specifies at least the name and mount path of the volume.

```json
{
    "volumes": [
        {
            "name": "etc"
        }
    ]
}
```

```json
{
    "volumeMounts": [
        {
            "name": "env",
            "mountPath": "/mnt/etc"
        }
    ]
}
```

Pods also support host volumes. A pod volume parameter can declare a `host` field that references a pre-existing file or directory on the agent.

```json
{
    "volumes": [
        {
            "name": "local",
            "host": "/user/local"
        }
    ]
}
```

**Note:** Data does not persist if pods are restarted.

#### Containerizers

Marathon pods support the [Mesos containerizer](http://mesos.apache.org/documentation/latest/mesos-containerizer/). The Mesos containerizer [supports multiple images, such as Docker](http://mesos.apache.org/documentation/latest/container-image/). [Learn more about running Docker containers on Marathon]({{ site.baseurl }}/docs/native-docker.html).

The following JSON specifies a Docker image for the pod:

```json
{
   "image":{
      "id":"mesosphere/marathon:latest",
      "kind":"DOCKER",
      "forcePull":false
   }
}
```

An optional `image.pullConfig` is supported too. Here is an example of
a pod pulling a Docker image from a private registry:

```json
{
    "id": "/simple-pod",
    "scaling": {
        "kind": "fixed",
        "instances": 1
    },
    "containers": [{
        "name": "container0",
        "exec": {
            "command": {
                "shell": "sleep 1000"
            }
        },
        "image": {
            "kind": "DOCKER",
            "id": "company/private-image",
            "pullConfig": {
                "secret": "configSecret"
            }
        },
        "resources": {
            "cpus": 1,
            "mem": 50.0
        }
    }],
    "secrets": {
        "configSecret": {
            "source": "/config"
        }
    }
}
```

For further details, please refer
to [Configuration of Docker images with Mesos containerizer]({{ site.baseurl }}/docs/native-docker.html).

## Create and Manage Pods

Use the `/v2/pods/` endpoint to create and manage your pods. [See the full API spec]({{ site.baseurl }}/docs/generated/api.html#v2_pods).

### Create

```bash
curl -X POST -H "Content-type: application/json" -d@<mypod>.json http://<ip>:<port>/v2/pods
```

Sample response:

```json
{
    "containers": [
        {
            "artifacts": [],
            "endpoints": [],
            "environment": {},
            "exec": {
                "command": {
                    "shell": "sleep 1000"
                }
            },
            "healthCheck": null,
            "image": null,
            "labels": {},
            "lifecycle": null,
            "name": "sleep1",
            "resources": {
                "cpus": 0.1,
                "disk": 0,
                "gpus": 0,
                "mem": 32
            },
            "user": null,
            "volumeMounts": []
        }
    ],
    "environment": {},
    "id": "/simplepod2",
    "labels": {},
    "networks": [
        {
            "labels": {},
            "mode": "host",
            "name": null
        }
    ],
    "scaling": {
        "instances": 2,
        "kind": "fixed",
        "maxInstances": null
    },
    "scheduling": {
        "backoff": {
            "backoff": 1,
            "backoffFactor": 1.15,
            "maxLaunchDelay": 3600
        },
        "placement": {
            "acceptedResourceRoles": [],
            "constraints": []
        },
        "upgrade": {
            "maximumOverCapacity": 1,
            "minimumHealthCapacity": 1
        }
    },
    "secrets": {},
    "user": null,
    "volumes": []
}
```

### Status

Get the status of all pods:

```bash
curl -X GET http://<ip>:<port>/v2/pods/::status
```

Get the status of a single pod:

```bash
curl -X GET http://<ip>:<port>/v2/pods/<pod-id>::status
```

### Delete

```bash
curl -X DELETE http://<ip>:<port>/v2/pods/<pod-id>
```

<a name="examples"></a>
## Example Pod Definitions

### Annotated simple pod definition

This pod, named `simple-pod` has a single container, `simpletask1`. The container pulls down an image (`python:3.5.2-alpine`) and runs a command. <!-- validated by suzanne 6/23/17 -->

```json
{
   "id":"/simple-pod",
   "containers":[
      {
         "name":"simpletask1",
         "exec":{
            "command":{
               "shell":"env && sleep 10000"
            }
         },
         "resources":{
            "cpus":0.1,
            "mem":32
         },
         "image":{
            "kind":"DOCKER",
            "id":"python:3.5.2-alpine"
         }
      }
   ],
   "networks":[
      {
         "mode":"host"
      }
   ]
}
```

### Basic pod fields

| Field                 | Type    | Value                                                                                                                                                                                                             |
|-----------------------|---------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `id` (required)         | string  | Unique ID for the pod.                                                                                                                                                                                            |
| `containers` (required) | array   | See [Basic pod container fields](#basic-pod-container-fields).                                                                                                                                                                                     |
| `volumes`               | array   | All volumes associated with the pod.                                                                                                                                                                              |
| `volumes.name`                | string  | Name of shared volume.                                                                                                                                                                                            |
| `volumes.host`                | string  | Absolute path of the file or directory on the agent, or else the relative path of the directory in the executor's sandbox. Useful for mapping directories that exist on the agent or within the executor sandbox. |
| `networks`              | array        |  Accepts the following parameters: `mode`, `name`, and `labels`.                                                                                                                                                                                                                 |
| `networks.mode`                | string  | Network mode: `host` or `container`. `host` uses the network namespace of the host. `container` uses virtual networking, and a virtual network name must be specified.                                            |
| `networks:name`                | string  | Required for `container` network mode.                                                                                                                                                                            |
| `networks.labels`              | object  | Key/value pairs (i.e., for passing metadata to Mesos modules).                                                                                                                                                    |
| `scaling`               | array        |  Accepts the following parameters: `kind`, `instances`, and `maxInstances`.                                                                                                                                                                                                                 |
| `scaling.kind`                | string  | Type of scaling. Only `fixed` is currently supported.                                                                                                                                                             |
| `scaling.instances`           | integer | Initial number of pod instances (default: 1).                                                                                                                                                                     |
| `scaling.maxInstances`        | integer | Maximum number of instances of this pod.                                                                                                                                                                          |

<a name="basic-pod-container-fields"></a>

### Basic pod container fields

| Field                    | Type    | Value                                                                                                      |
|--------------------------|---------|------------------------------------------------------------------------------------------------------------|
| `containers` (required)    | array   | Container definitions for all containers that belong to a pod.                                             |
| `containers.name`                   | string  | Unique name for the container.                                                                             |
| `containers.exec`                   | object  |  Accepts the `command` parameter.                                                                                                          |
| `containers.exec.command`            | object  | Command executed by Mesos.                                                                                 |
| `containers.exec.command.shell`          | string  | Command to execute. If using container entrypoint, use an empty string.                                    |
| `containers.exec.overrideEntrypoint` | boolean | If `command` is supplied, this is implicitly set to `true`. To use the default entrypoint, set to `false`. |
| `containers:resources` (required)   | object  | Container specifications for resources.                                                                    |
| `containers.resources.cpus`               | number  | CPU shares (default: 1.0).                                                                                 |
| `containers.resources.mem`                | number  | Memory resources in MiB (default: 128).                                                                    |
| `containers.resources.disk`               | double  | Disk resources in MiB (default: 128).                                                                      |
| `containers.resources.gpus`               | integer | GPU resources (default: 0).                                                                                |
| `containers.image`                  | object  | If `image` is omitted, the Mesos containerizer is used.                                                    |
| `containers.image.kind`               | string  | Container image format (`DOCKER` or `APPC`).                                                               |
| `containers.image.id`                 | string  | Container image tag.                                                                                       |
| `containers.image.forcePull`          | boolean | Set to true to always pull image (default: false).                                                         |
| `containers.volumeMounts`           | array   |  Accepts the following parameters: `name` and `mountPath`.                                                                                                          |
| `containers.volumeMounts.name`               | string  | Name of the shared volume (must be a valid volume defined at the pod layer).                               |
| `containers.volumeMounts.mountPath`          | string  | Container path to mount volume.                                                                            |
| `containers.endpoints`              | array   | Array of objects.                                                                                          |
| `containers.endpoints.name`               | string  | Unique name of port.                                                                                       |
| `containers.endpoints.containerPort`      | number  | The container point the container task is listening on. Required if network mode is `container`.           |
| `containers.endpoints.hostPort`           | number  | Mapped port on host. If set to `0`, Marathon dynamically allocates the port.                                 |
| `containers.endpoints.protocol`           | array   | Protocol of port (`tcp` or `http`).                                                                        |
| `containers.endpoints.labels`						 | object  | Metadata as key/value pairs.																																								|

<a name="multi-pod"></a>

### Annotated multi-pod with all parameters

The example below shows a pod, `test-pod`, with three containers, `healthtask1`, `healthtask2`, and `clienttask`. The pod makes uses shared volumes and the native DC/OS virtual networking solution.

```
{
   "id":"/test-pod",
   "labels":{
      "pod_label":"pod"
   },
   "environment":{
      "POD_ENV":"pod"
   },
   "containers":[
      {
         "name":"healthtask1",
         "exec":{
            "command":{
               "shell":"./read-write-server.py 8080 mount1/test-file.txt"
            }
         },
         "resources":{
            "cpus":0.1,
            "mem":32,
            "disk":32,
            "gpus":0
         },
         "endpoints":[
            {
               "name":"httpendpoint",
               "containerPort":8080,
               "hostPort":0,
               "protocol":[
                  "tcp"
               ],
               "labels":{
                  "ep1_label":"ep1"
               }
            }
         ],
         "image":{
            "kind":"DOCKER",
            "id":"python:3.5.2-alpine"
         },
         "environment":{
            "C1_ENV":"c1"
         },
         "healthCheck":{
            "http":{
               "endpoint":"httpendpoint",
               "path":"/ping",
               "scheme":"HTTP"
            },
            "gracePeriodSeconds":30,
            "intervalSeconds":5,
            "maxConsecutiveFailures":3,
            "timeoutSeconds":3,
            "delaySeconds":2
         },
         "volumeMounts":[
            {
               "name":"sharedvolume",
               "mountPath":"mount1"
            }
         ],
         "artifacts":[
            {
               "uri":"https://s3-us-west-2.amazonaws.com/mesos-soak-cluster/read-write-server.py",
               "extract":false,
               "executable":true,
               "cache":true,
               "destPath":"read-write-server.py"
            }
         ],
         "labels":{
            "c1_label":"c1"
         }
      },
      {
         "name":"healthtask2",
         "exec":{
            "command":{
               "shell":"./read-write-server.py 8081 mount2/test-file.txt"
            }
         },
         "resources":{
            "cpus":0.1,
            "mem":32,
            "disk":32,
            "gpus":0
         },
         "endpoints":[
            {
               "name":"httpendpoint2",
               "containerPort":8081,
               "hostPort":0,
               "protocol":[
                  "tcp"
               ],
               "labels":{
                  "ep2_label":"ep2"
               }
            }
         ],
         "image":{
            "kind":"DOCKER",
            "id":"python:3.5.2-alpine"
         },
         "environment":{
            "C2_ENV":"c2"
         },
         "healthCheck":{
            "http":{
               "endpoint":"httpendpoint2",
               "path":"/ping",
               "scheme":"HTTP"
            },
            "gracePeriodSeconds":30,
            "intervalSeconds":5,
            "maxConsecutiveFailures":3,
            "timeoutSeconds":3,
            "delaySeconds":2
         },
         "volumeMounts":[
            {
               "name":"sharedvolume",
               "mountPath":"mount2"
            }
         ],
         "artifacts":[
            {
               "uri":"https://s3-us-west-2.amazonaws.com/mesos-soak-cluster/read-write-server.py",
               "extract":false,
               "executable":true,
               "cache":true,
               "destPath":"read-write-server.py"
            }
         ],
         "labels":{
            "c2_label":"c2"
         }
      },
      {
         "name":"clienttask",
         "exec":{
            "command":{
               "shell":"while true; do sleep 5 && curl -X GET localhost:8080/write && curl -X GET localhost:8081/read; done"
            }
         },
         "resources":{
            "cpus":0.1,
            "mem":32,
            "disk":32,
            "gpus":0
         },
         "endpoints":[

         ],
         "environment":{
            "C3_ENV":"c3"
         },
         "volumeMounts":[

         ],
         "artifacts":[

         ],
         "labels":{
            "c3_label":"c3"
         }
      }
   ],
   "secrets":{

   },
   "volumes":[
      {
         "name":"sharedvolume"
      }
   ],
   "networks":[
      {
         "name":"dcos",
         "mode":"container",
         "labels":{
            "net_label":"net"
         }
      }
   ],
   "scaling":{
      "kind":"fixed",
      "instances":1,
      "maxInstances":null
   },
   "scheduling":{
      "backoff":{
         "backoff":1,
         "backoffFactor":1.15,
         "maxLaunchDelay":3600
      },
      "upgrade":{
         "minimumHealthCapacity":1,
         "maximumOverCapacity":1
      },
      "placement":{
         "constraints":[

         ],
         "acceptedResourceRoles":[

         ]
      },
      "killSelection":"YOUNGEST_FIRST",
      "unreachableStrategy":{
         "inactiveAfterSeconds":900,
         "expungeAfterSeconds":604800
      }
   },
   "executorResources":{
      "cpus":0.1,
      "mem":32,
      "disk":10
   }
}
```

### Additional pod fields

| Field                       | Type     | Value                                                                                                                          |
|-----------------------------|----------|--------------------------------------------------------------------------------------------------------------------------------|
| `labels`                      | object   | Pod metadata as key/value pairs.                                                                                               |
| `environment`                 | object   | Environment variables at the pod level. All pod containers will inherit these environment variables. Must be capitalized.      |
| `secrets`                     | object   | The fully qualified path to the secret in the store.                                                                           |
| `scheduling`                  | object   | Defines exponential backoff behavior for faulty apps to prevent sandboxes from filling up.                            |
| `scheduling.backoff`               | number   | Initial backoff (seconds) applied when a launched instance fails (default: 1).                                                 |
| `scheduling.backoffFactor`         | number   | Factor applied to current backoff to determine the new backoff (default: 1.15).                                                |
| `scheduling.maxLaunchDelay`        | number   | Maximum backoff (seconds) applied when subsequent failures are detected (default: 3600).                                       |
| `upgrade`                     | object   | Upgrade strategy that controls pod updates.                                                                                    |
| `upgrade.minimumHealthCapacity` | number   | Number between 0 and 1 that represents the minimum number of healthy nodes to maintain during upgrade (default: 1).               |
| `upgrade.maximumOverCapacity`   | number   | Number between 0 and 1 representing the maximum number of additional instances to launch during upgrade (default: 1).          |
| `placement`                   | object   | Controls placement of pod tasks.                                                                                               |
| `placement.constraints`           | string[] | Constraints control the placement policy of pod tasks. Options: `UNIQUE`, `CLUSTER`, `GROUP_BY`, `LIKE`, `UNLIKE`, `MAX_PER`.  |
| `placement.acceptedResourceRoles` | string[] | List of resource roles. The Marathon component will only consider resource offers with roles on this list for this pod's tasks.              |
| `killSelection`               | string   | Defines which instance is killed first when an app is in an over-provisioned state. Options: `YOUNGEST_FIRST`, `OLDEST_FIRST`. |
| `unreachableStrategy`         |          | Behavior when agents are partitioned from masters.                                                                             |
| `killSelection.inactiveAfterSeconds`  | integer  | Time in seconds to wait before replacing task (default: 900).                                                                  |
| `killSelection.expungeAfterSeconds`   | integer  | Time in seconds to wait for tasks to come back before expunging (default: 603800).                                             |
| `executorResources`           | object   | Resources reserved for the pod executor.                                                                                       |
| `executorResources.cpus`                  | number   | CPU shares (default: 0.1).                                                                                                     |
| `executorResources.mem`                   | number   | Memory resources in MiB (default: 32).                                                                                         |
| `executorResources.disk`                  | number   | Disk resources in MiB (default: 10.0),                                                                                         |

### Additional pod container fields

| Field                        | Type    | Value                                                                                                                   |
|------------------------------|---------|-------------------------------------------------------------------------------------------------------------------------|
| `labels`                       | object  | Container metadata as key/value pairs.                                                                                  |
| `environment`                  | object  | Container environment variables. Can override pod environment variables. Must be capitalized.                           |
| `healthCheck`                  |  object       |  Accepts the following parameters: `http`, `tcp`, and `exec`.                                                                                                                       |
| `healthCheck.http`                   |         | Protocol type. Options: `http`, `tcp`, `exec`.                                                                          |
| `healthCheck.http.endpoint`           | string  | Endpoint name to use.                                                                                                   |
| `healthCheck.http.path`               | string  | Path to the endpoint exposed by the task that provides health status.                                                   |
| `healthCheck.http.scheme`             | string  | For httpHealthCheck, use `http`.                                                                                        |
| `healthCheck.gracePeriodSeconds`     | integer | Interval to ignore health check failures after a task is first started or until a task is first healthy (default: 300). |
| `healthCheck.intervalSeconds`        | integer | Interval between health checks (default: 60).                                                                           |
| `healthCheck.maxConsecutiveFailures` | integer | Number of consecutive failures before task is killed (default: 3).                                                      |
| `healthCheck.timeoutSeconds`         | integer | Time to wait until health check is complete (default: 20).                                                              |
| `healthCheck.delaySeconds`           | integer | Time to wait until starting health check (default: 2).                                                                  |
| `artifacts`                    | array   | Array of artifact objects                                                                                               |
| `healthCheck.uri`                    | strings | URI to resources to download (i.e., `.tgz`, `tar.gz`, `.zip`, `.txz`, etc).                                                     |
| `healthCheck.extract`                | boolean | Extract fetched artifact.                                                                                               |
| `healthCheck.executable`             | boolean | Set fetched artifact as executable.                                                                                     |
| `healthCheck.cache`                  | boolean | Cache fetched artifact.                                                                                                 |
| `healthCheck.destPath`               | strings | Destination path of artifact.                                                                                           |

### A pod with multiple containers

The following pod definition specifies a pod with 3 containers. <!-- Validated by suzanne 6-23-17 -->

```json
{
   "id":"/pod-with-multiple-containers",
   "version":"2017-01-03T18:21:19.31Z",
   "containers":[
      {
         "name":"sleep1",
         "exec":{
            "command":{
               "shell":"sleep 1000"
            }
         },
         "resources":{
            "cpus":0.01,
            "mem":32,
            "disk":0,
            "gpus":0
         }
      },
      {
         "name":"sleep2",
         "exec":{
            "command":{
               "shell":"sleep 1000"
            }
         },
         "resources":{
            "cpus":0.01,
            "mem":32,
            "disk":0,
            "gpus":0
         }
      },
      {
         "name":"sleep3",
         "exec":{
            "command":{
               "shell":"sleep 1000"
            }
         },
         "resources":{
            "cpus":0.01,
            "mem":32,
            "disk":0,
            "gpus":0
         }
      }
   ],
   "networks":[
      {
         "mode":"host"
      }
   ],
   "scaling":{
      "kind":"fixed",
      "instances":10,
      "maxInstances":null
   },
   "scheduling":{
      "backoff":{
         "backoff":1,
         "backoffFactor":1.15,
         "maxLaunchDelay":3600
      },
      "upgrade":{
         "minimumHealthCapacity":1,
         "maximumOverCapacity":1
      },
      "killSelection":"Youngest_First",
      "unreachableStrategy":{
         "inactiveAfterSeconds":900,
         "expungeAfterSeconds":604800
      }
   },
   "executorResources":{
      "cpus":0.1,
      "mem":32,
      "disk":10
   }
}
```

### A Pod that Uses Ephemeral Volumes

The following pod definition specifies an ephemeral volume called `v1`. <!-- Validated by suzanne 6-23-17 -->

```json
{
  "id": "/with-ephemeral-vol",
  "version": "2017-01-03T17:36:39.389Z",
  "containers": [
    {
      "name": "ct1",
      "exec": {
        "command": {
          "shell": "while true; do echo the current time is $(date) > ./jdef-v1/clock; sleep 1; done"
        }
      },
      "resources": {
        "cpus": 0.1,
        "mem": 32,
        "disk": 0,
        "gpus": 0
      },
      "volumeMounts": [
        {
          "name": "v1",
          "mountPath": "jdef-v1"
        }
      ]
    },
    {
      "name": "ct2",
      "exec": {
        "command": {
          "shell": "while true; do cat ./etc/clock; sleep 1; done"
        }
      },
      "resources": {
        "cpus": 0.1,
        "mem": 32,
        "disk": 0,
        "gpus": 0
      },
      "volumeMounts": [
        {
          "name": "v1",
          "mountPath": "etc"
        }
      ]
    }
  ],
  "volumes": [
    {
      "name": "v1"
    }
  ],
  "networks": [
    {
      "mode": "host"
    }
  ],
  "scaling": {
    "kind": "fixed",
    "instances": 1,
    "maxInstances": null
  },
  "scheduling": {
    "backoff": {
      "backoff": 1,
      "backoffFactor": 1.15,
      "maxLaunchDelay": 3600
    },
    "upgrade": {
      "minimumHealthCapacity": 1,
      "maximumOverCapacity": 1
    },
    "killSelection": "Youngest_First",
    "unreachableStrategy": {
      "inactiveAfterSeconds": 900,
      "expungeAfterSeconds": 604800
    }
  },
  "executorResources": {
    "cpus": 0.1,
    "mem": 32,
    "disk": 10
  }
}
```

### IP-per-Pod Networking

The following pod definition specifies a virtual (user) network named `dcos`. The `networks:mode:container` field creates the virtual network. The `name` field is optional. If you have installed DC/OS using [our AWS templates](/docs/1.9/installing/cloud/aws/), the default virtual network name is `dcos`. <!-- Validated by suzanne 6-23-17 -->

```json
{
   "id":"/pod-with-virtual-network",
   "scaling":{
      "kind":"fixed",
      "instances":1
   },
   "containers":[
      {
         "name":"sleep1",
         "exec":{
            "command":{
               "shell":"sleep 1000"
            }
         },
         "resources":{
            "cpus":0.1,
            "mem":32
         }
      }
   ],
   "networks":[
      {
         "mode":"container",
         "name":"dcos"
      }
   ]
}
```

This pod declares a “web” endpoint that listens on port 80. <!-- Validated by suzanne 6-23-17 -->

```json
{
   "id":"/pod-with-endpoint",
   "containers":[
      {
         "name":"simple-docker",
         "resources":{
            "cpus":1,
            "mem":128,
            "disk":0,
            "gpus":0
         },
         "image":{
            "kind":"DOCKER",
            "id":"nginx"
         },
         "endpoints":[
            {
               "name":"web",
               "containerPort":80,
               "protocol":[
                  "http"
               ]
            }
         ]
      }
   ],
   "networks":[
      {
         "mode":"container"
      }
   ]
}
```

This pod adds a healthcheck that references the `web` endpoint.  Mesos will execute an HTTP request against `<container_ip>:80`. The health check will pass if Mesos receives an HTTP 200 response.

```json
{
   "id":"/pod-with-healthcheck",
   "containers":[
      {
         "name":"simple-docker",
         "resources":{
            "cpus":1,
            "mem":128,
            "disk":0,
            "gpus":0
         },
         "image":{
            "kind":"DOCKER",
            "id":"nginx"
         },
         "endpoints":[
            {
               "name":"web",
               "containerPort":80,
               "protocol":[
                  "http"
               ]
            }
         ],
         "healthCheck":{
            "http":{
               "endpoint":"web",
               "path":"/"
            }
         }
      }
   ],
   "networks":[
      {
         "mode":"container"
      }
   ]
}
```

### Complete Pod
The following pod definition can serve as a reference to create more complicated pods.

```json
{
  "id": "/complete-pod",
  "labels": {
    "owner": "zeus",
    "note": "Away from olympus"
  },
  "environment": {
    "XPS1": "Test"
  },
  "volumes": [
    {
      "name": "etc",
      "host": "/etc"
    }
  ],
  "networks": [
    {
     "mode": "container",
     "name": "dcos"
    }
  ],
  "scaling": {
    "kind": "fixed",
    "instances": 1
  },
  "scheduling": {
    "backoff": {
      "backoff": 1,
      "backoffFactor": 1.15,
      "maxLaunchDelay": 3600
    },
    "upgrade": {
      "minimumHealthCapacity": 1,
      "maximumOverCapacity": 1
    },
    "placement": {
      "constraints": [],
      "acceptedResourceRoles": []
    }
  },
  "containers": [
    {
      "name": "container1",
      "resources": {
        "cpus": 1,
        "mem": 128,
        "disk": 0,
        "gpus": 0
      },
      "endpoints": [
        {
          "name": "http-endpoint",
          "containerPort": 80,
          "hostPort": 0,
          "protocol": [ "HTTP" ],
          "labels": {}
        }
      ],
      "image": {
        "id": "nginx:latest",
        "kind": "DOCKER",
        "forcePull": false
      },
      "environment": {
        "XPS1": "Test"
      },
      "user": "root",
      "healthCheck": {
        "gracePeriodSeconds": 30,
        "intervalSeconds": 5,
        "maxConsecutiveFailures": 3,
        "timeoutSeconds": 4,
        "delaySeconds": 2,
        "http": {
          "path": "/",
          "scheme": "HTTP",
          "endpoint": "http-endpoint"
        }
      },
      "volumeMounts": [
        {
          "name": "etc",
          "mountPath": "/mnt/etc",
          "readOnly": true
        }
      ],
      "artifacts": [
        {
          "uri": "https://ftp.gnu.org/gnu/glibc/glibc-2.25.tar.gz",
          "executable": false,
          "extract": true,
          "cache": true,
          "destPath": "glibc-2.25.tar.gz"
        }
      ],
      "labels": {
        "owner": "zeus",
        "note": "Away from olympus"
      },
      "lifecycle": {
        "killGracePeriodSeconds": 60
      }
    }
  ]
}
```

## Limitations

- If a pod belongs to a group that declares dependencies, these dependencies are implicit for the pod. If a group deployment operation is blocked because of a dependency, and that group contains a pod, then that pod's deployment is also blocked.

- Pods cannot be modified by the `/v2/groups/` endpoint. They are read-only at the `/v2/groups/` endpoint.

- Pods only support Mesos-based health checks.

- There is no service port tracking or allocation for pods.

- Pod definitions do not provide a field to declare dependencies on other v2 API objects.

- Pods do not support readiness checks.

- Killing any task of a pod will result in the suicide of the pod executor that owns the task, which means that all of the applications in that pod instance will die.
