---
title: Pods
---

# Pods

Marathon supports the creation and management of pods. Pods enable you to share storage, networking, and other resources among a group of applications on a single agent and address them as one group rather than as separate applications, similar to a virtual machine. Pods allow quick, convenient coordination between applications that need to work together, for instance a database and web server that make up a content management system.

Currently, Marathon pods can only be created and administered via the `/v2/pods/` endpoint of the REST API, not via the web interface.

# Features

- Co-located containers.
- Pod-level resource isolation.
- Sandbox and ephemeral volumes that are shared among pod containers.
- Configurable sandbox mount point for each container.
- Pod-level health checks.

# Quick Start

1. Run the following REST call:

    ```bash
    $ http POST <ip>:<port>/v2/pods <<EOF
    > {
    >   "id": "/simplepod",
    >   "scaling": { "kind": "fixed", "instances": 1 },
    >   "containers": [
    >     {
    >       "name": "sleep1",
    >       "exec": { "command": { "shell": "sleep 1000" } },
    >       "resources": { "cpus": 0.1, "mem": 32 }
    >     }
    >   ],
    >   "networks": [ {"mode": "host"} ]
    > }
    > EOF
    ```

1. Verify the status of your new pod:

    ```bash
    http GET <ip>:<port>/v2/pods/simplepod::status
    ```

1. Delete your pod:

    ```bash
    http DELETE <ip>:<port>/v2/pods/simplepod
    ```

# Technical Overview

Pods are are members of Groups.* A pod instance’s containers are launched together, atomically, via the Mesos LAUNCH_GROUP call. Containers in pods share networking namespace and ephemeral volumes.

\* Pods cannot be modified by the `/v2/groups/` endpoint, however. Pods are modified via the `/v2/pods/` endpoint.

## Networking
Marathon pods only support the [Mesos containerizer](http://mesos.apache.org/documentation/latest/mesos-containerizer/), which simplifies networking among containers. <!-- is this true for vanilla marathon? --> The containers of each pod instance share a network namespace and can communicate over localhost. 

Containers in pods are created with endpoints. Other applications communicate with pods by addressing those endpoints. If you specify a container network without a name in a pod definition, it will be assigned to this default network.

In your pod definition you can declare a `host` or `container` network type. Pods created with `host` type share the network namespace of the host. Pods created with `container` type use virtual networking. If you specify the `container` network type, you must also declare a virtual network name in the `name` field. See the [Examples](link) section for the full JSON.

## Ephemeral Storage
Containers within a pod share ephemeral storage. You can mount volumes by different names on each container in the pod.

## Pod Definitions
Pods are configured via a JSON pod definition, which is similar to an [application definition](http://mesosphere.github.io/marathon/docs/application-basics.html). You must declare the resources required by each container in the pod, even if Mesos is isolating at a higher (pod) level.  See the [Examples](link) section for complete pod definitions.

### Secrets

Specify a secret in the `secrets` field of your pod definition. The argument should be the fully qualified path to the secret in the store.

```
{
	"secrets": /fully/qualified/path/
}
```

### Volumes

Marathon pods support several types of volumes are supported: ephemeral, persistent local and persistent external. <!-- is this true? --> All volumes are defined at the pod level. Your pod definition must include a `volumes` field that specifies at least the name of the volume <!-- check options in API docs --> and a `volumeMounts` field that specifies at least the name and mount path of the volume.

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

### Containerizers

Marathon pods support the [Mesos containerizer](http://mesos.apache.org/documentation/latest/mesos-containerizer/). The Mesos containerizer [allows you to specify alternative container images, such as Docker](http://mesos.apache.org/documentation/latest/container-image/). [Learn more about running Docker containers on Marathon](http://mesosphere.github.io/marathon/docs/native-docker.html).

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

### Network

# Create and Manage Pods

Use the `v2/pods/` endpoint to create and manage your pods. [See the full API spec](http://mesosphere.github.io/marathon/docs/generated/api.html#v2_pods).

## Create

<!-- can this accept a json file like this as an argument? -->
```json
 $ http POST <ip>:<port>/v2/pods mypod.json
```

Sample response:

```json
HTTP/1.1 201 Created
Cache-Control: no-cache, no-store, must-revalidate
Content-Type: application/json; qs=2
Date: Tue, 18 Oct 2016 21:07:49 GMT
Expires: 0
Location: http://localhost:8080/v2/pods/simplepod2
Marathon-Deployment-Id: 8b591de8-6f2d-4036-870e-7571912d3522
Pragma: no-cache
Server: Jetty(9.3.z-SNAPSHOT)
Transfer-Encoding: chunked
X-Marathon-Leader: http://71c812cd6810:8080

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
    "version": "2016-10-18T21:07:49.94Z",
    "volumes": []
}
```

## Update

<!-- how do I update? -->

## Status

Get the status of all pods:

```bash
http GET <ip>:<port>/v2/pods/::status
```

Get the status of a single pod:

```bash
http GET <ip>:<port>/v2/pods/<pod-id>::status
```

## Delete

```bash
http DELETE <ip>:<port>/v2/pods/<pod-id>
```

# Example Pod Definitions

## A Pod with Multiple Containers
	
The following pod definition specifies a pod with 3 containers. <!-- Validated. JSH 9/30/16 -->

```json
{
  "id": "/pod-with-multiple-containers",
  "labels": {
    "values": {}
  },
  "version": "2016-09-22T09:18:05.928Z",
  "user": null,
  "environment": null,
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
      },
      "endpoints": [],
      "image": null,
      "environment": null,
      "user": null,
      "healthCheck": null,
      "volumeMounts": [],
      "artifacts": [],
      "labels": null,
      "lifecycle": null
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
      },
      "endpoints": [],
      "image": null,
      "environment": null,
      "user": null,
      "healthCheck": null,
      "volumeMounts": [],
      "artifacts": [],
      "labels": null,
      "lifecycle": null
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
      },
      "endpoints": [],
      "image": null,
      "environment": null,
      "user": null,
      "healthCheck": null,
      "volumeMounts": [],
      "artifacts": [],
      "labels": null,
      "lifecycle": null
    }
  ],
  "secrets": null,
  "volumes": [],
  "networks": [
    {
      "mode": "host"
    }
  ],
  "scaling": {
    "kind": "fixed",
    "instances": 10,
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
    "placement": {
      "constraints": [],
      "acceptedResourceRoles": []
    }
  }
}
```

## A Pod that Uses Ephemeral Volumes

The following pod definition specifies an ephemeral volume called `v1`. <!-- Validated. JSH 9/30/16 -->

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

## IP-per-Pod Networking

The following pod definition specifies a virtual (user) network named `my-virtual-network-name`. <!-- Validated. JSH 9/30/16 -->

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

This pod declares a “web” endpoint that listens on port 80. <!-- Validated. JSH 9/30/16 3:23pm -->

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

This pod adds a health check that references the “web” endpoint; mesos will execute an HTTP request against `http://<master-ip>:80/ping`. <!-- Validated. JSH 9/30/16 3:11pm -->

```json
{
  "id": "/pod-with-healthcheck",
  "scaling": { "kind": "fixed", "instances": 1 },
  "containers": [
    {
      "name": "sleep1",
      "exec": { "command": { "shell": "sleep 1000" } },
      "resources": { "cpus": 0.1, "mem": 32 },
      "endpoints": [ { "name": "web", "containerPort": 80, "protocol": [ "http" ] } ],
      "healthCheck": { "http": { "endpoint": "web", "path": "/ping" } }
    }
  ],
  "networks": [ { "mode": "container", "name": "my-virtual-network-name" } ]
}
```

## Complete Pod 
The following pod definition can serve as a reference to create more complicated pods. <!-- Validated. JSH 9/30/16 3:11pm -->

```
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
    },
    "placement": {
      "constraints": [],
      "acceptedResourceRoles": []
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
          "labels": {}
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
          "destPath": "newname.zip"
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

# Limitations

- If a pod belongs to a group that declares dependencies, these dependencies are implicit for the pod.

- Pods are are members of Groups, but they cannot be modified by the `/v2/groups/` endpoint. At the `v2/groups` endpoint, they are read-only.

- Pods only support Mesos-based health checks.

- There is no service port tracking or allocation for pods.

- Pod definitions do not provide a field to declare dependencies on other v2 API objects.

- Pods do not support readiness checks.

- Killing any task of a pod will result in the suicide of the pod executor that owns the task.

- No support for “storeUrls” (see v2 /apps).
