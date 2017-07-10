---
title: Pods
---

# Pods

As of version 1.4, Marathon supports the creation and management of pods. Pods enable you to share storage, networking, and other resources among a group of applications on a single agent, address them as one group rather than as separate applications, and manage health as a unit.

Pods allow quick, convenient coordination between applications that need to work together, for instance a primary service and a related analytics service or log scraper. Pods are particularly useful for transitioning legacy applications to a microservices-based architecture.

Currently, Marathon pods can only be created and administered via the `/v2/pods/` endpoint of the REST API, not via the web interface.

**Note:** Pods are not supported in the `strict` security mode of Enterprise DC/OS and are only available as of Marathon 1.4.

## Features

- Co-located containers.
- Pod-level resource isolation.
- Pod-level sandbox and ephemeral volumes.
- Pod-level health checks.

## Quick Start

1. Run the following REST call, substituting your IP and port for `<ip>` and `<port>`:

        $ curl -X POST -H "Content-type: application/json" -d@<mypod>.json http://<ip>:<port>/v2/pods <<EOF
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

The Mesos containerizer simplifies networking by allowing the containers of each pod instance to share a network namespace and communicate over localhost. If you specify a container network without a name in a pod definition, it will be assigned to the default network.

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
 $ curl -X POST -H "Content-type: application/json" -d@<mypod>.json http://<ip>:<port>/v2/pods
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

### A Pod with Multiple Containers

The following pod definition specifies a pod with 3 containers.

```json
{
  "id": "/pod-with-multiple-containers",
  "labels": {
    "values": {}
  },
  "containers": [
    {
      "name": "sleep1",
      "exec": {
        "command": {
          "shell": "sleep 1000"
        }
      },
      "resources": {
        "cpus": 0.1,
        "mem": 32,
        "disk": 0,
        "gpus": 0
      }
    },
    {
      "name": "sleep2",
      "exec": {
        "command": {
          "shell": "sleep 1000"
        }
      },
      "resources": {
        "cpus": 0.1,
        "mem": 32,
        "disk": 0,
        "gpus": 0
      }
    },
    {
      "name": "sleep3",
      "exec": {
        "command": {
          "shell": "sleep 1000"
        }
      },
      "resources": {
        "cpus": 0.1,
        "mem": 32,
        "disk": 0,
        "gpus": 0
      }
    }
  ],
  "networks": [
    {
      "mode": "host"
    }
  ],
  "scaling": {
    "kind": "fixed",
    "instances": 10
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
    }
  }
}
```

### A Pod that Uses Ephemeral Volumes

The following pod definition specifies an ephemeral volume called `v1`.

```json
{
  "id": "/with-ephemeral-vol",
  "scaling": { "kind": "fixed", "instances": 1 },
  "containers": [
    {
      "name": "ct1",
      "resources": {
        "cpus": 0.1,
        "mem": 32
      },
      "exec": { "command": { "shell": "while true; do echo the current time is $(date) > ./jdef-v1/clock; sleep 1; done" } },
      "volumeMounts": [
        {
          "name": "v1",
          "mountPath": "jdef-v1"
        }
      ]
    },
    {
      "name": "ct2",
      "resources": {
        "cpus": 0.1,
        "mem": 32
      },
      "exec": { "command": { "shell": "while true; do cat ./etc/clock; sleep 1; done" } },
      "volumeMounts": [
        {
          "name": "v1",
          "mountPath": "etc"
        }
      ]
    }
  ],
  "networks": [
    { "mode": "host" }
  ],
  "volumes": [
    { "name": "v1" }
  ]
}
```

### IP-per-Pod Networking

The following pod definition specifies a virtual (user) network named `my-virtual-network-name`.

```json
{
  "id": "/pod-with-virtual-network",
  "scaling": { "kind": "fixed", "instances": 1 },
  "containers": [
    {
      "name": "sleep1",
      "exec": { "command": { "shell": "sleep 1000" } },
      "resources": { "cpus": 0.1, "mem": 32 }
    }
  ],
  "networks": [ { "mode": "container", "name": "my-virtual-network-name" } ]
}
```

<a name="endpoints"></a>
This pod declares a “web” endpoint that listens on port 80.

```json
{
  "id": "/pod-with-endpoint",
  "scaling": { "kind": "fixed", "instances": 1 },
  "containers": [
    {
      "name": "sleep1",
      "exec": { "command": { "shell": "sleep 1000" } },
      "resources": { "cpus": 0.1, "mem": 32 },
      "endpoints": [ { "name": "web", "containerPort": 80, "protocol": [ "http" ] } ]
    }
  ],
  "networks": [ { "mode": "container", "name": "my-virtual-network-name" } ]
}
```

This pod adds a health check that references the “web” endpoint; mesos will execute an HTTP request against `http://<master-ip>:80/ping`.

```json
{
  "id": "/pod-with-healthcheck",
  "scaling": { "kind": "fixed", "instances": 1 },
  "containers": [
    {
      "name": "sleep1",
      "exec": { "command": { "shell": "sleep 1000" } },
      "resources": { "cpus": 0.1, "mem": 32 },
      "image": {
        "id": "nginx",
        "kind": "DOCKER"
      },
      "endpoints": [ { "name": "web", "containerPort": 80, "protocol": [ "http" ] } ],
      "healthCheck": { "http": { "endpoint": "web", "path": "/ping" } }
    }
  ],
  "networks": [ { "mode": "container", "name": "my-virtual-network-name" } ]
}
```

### Comprehensive Pod
The following pod definition can serve as a reference to create more complicated pods. Information about the different properties can be found in the documentation for Marathon applications.

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
      "host": "/hdd/tools/docker/registry"
    }
  ],
  "networks": [
    {
      "mode": "host"
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
    }
  },
  "containers": [
    {
      "name": "container1",
      "exec": {
        "command": {
          "shell": "sleep 100"
        },
        "overrideEntrypoint": false
      },
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
          "protocol": [ "http" ],
        }
      ],
      "image": {
        "id": "mesosphere/marathon:latest",
        "kind": "DOCKER",
        "forcePull": false
      },
      "environment": {
        "XPS1": "Test"
      },
      "user": "root",
      "healthCheck": {
        "gracePeriodSeconds": 30,
        "intervalSeconds": 2,
        "maxConsecutiveFailures": 3,
        "timeoutSeconds": 20,
        "delaySeconds": 2,
        "http": {
          "path": "/health",
          "scheme": "HTTP",
          "endpoint": "http-endpoint"
        }
      },
      "volumeMounts": [
        {
          "name": "env",
          "mountPath": "/mnt/etc",
          "readOnly": true
        }
      ],
      "artifacts": [
        {
          "uri": "https://foo.com/archive.zip",
          "executable": false,
          "extract": true,
          "cache": true,
          "destPath": "/path/to/archive.zip"
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
