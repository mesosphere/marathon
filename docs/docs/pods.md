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

# Pod Definitions
Pods are configured via a JSON pod definition, which is similar to an [application definition](http://mesosphere.github.io/marathon/docs/application-basics.html). You must declare the resources required by each container in the pod, even if Mesos is isolating at a higher (pod) level.  See the [Examples](link) section for complete pod definitions.

## Secrets

Specify a secret in the `secrets` field of your pod definition. The argument should be the fully qualified path to the secret in the store.

```
{
	"secrets": /fully/qualified/path/
}
```

## Volumes

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

## Containerizers

Starting with Marathon 1.3 we will support the unified containerizer. So we will support the following container types: `mesos-docker`, `mesos-appc`.

```
code example...
```

# Create and Manage Pods

Use the `v2/pods/` endpoint to create and manage your pods.

## Create

## Update

## Status
Several categories of status
	Volumes
	Endpoints
	Container
	Network
	Pods (Instance and Model)

## Delete

# Networking
Pods only support the [DC/OS Universal containerizer runtime](https://dcos.io/docs/1.9/usage/containerizers/), which simplifies networking among containers. <!-- is this true for vanilla marathon? --> The containers of each pod instance share a network namespace and can communicate over localhost. 

Containers in pods are created with endpoints. Other applications communicate with pods by addressing those endpoints. If you specify a container network without a name in a pod definition, it will be assigned to this default network.

In your pod definition you can declare a `host` or `container` network type. Pods created with `host` type share the network namespace of the host. Pods created with `container` type use virtual networking. If you specify the `container` network type, you must also declare a virtual network name in the `name` field. See the [Examples](link) section for the full JSON.

# Ephemeral Storage
Containers within a pod share ephemeral storage. You can mount volumes by different names on each container in the pod.

# Limitations

- If a pod belongs to a group that declares dependencies, these dependencies are implicit for the pod.

- Pods are are members of Groups, but they cannot be modified by the `/v2/groups/` endpoint. At the `v2/groups` endpoint, they are read-only.

- Pods only support Mesos-based health checks.

- There is no service port tracking or allocation for pods.

- Pod definitions do not provide a field to declare dependencies on other v2 API objects.

- Pods do not support readiness checks

- Killing any task of a pod will result in the suicide of the pod executor that owns the task

- No support for “storeUrls” (see v2 /apps)
