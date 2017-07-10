---
title: Migrating Tools to the 1.5 Networking API
---

# Migrating Tools to the 1.5 Networking API

## Overview
The networking section of the Marathon API has changed significantly in version 1.5. Marathon can still accept requests using the 1.4 version of the API, but it will always reply with the 1.5 version of the app definition.

This WILL likely break tools that consume networking-related fields of the application definition.

This document contains the high-level structural differences between the 1.4 and 1.5 API versions. You can read more details regarding the networking section and the correct values for every field in the [networking documentation]({{ site.baseurl }}/docs/networking.html).

### Important notes

Marathon now supports applications attached to more than one network. However, additional rules apply when you specify more than one network:

- If a *portMapping* has defined a `hostPort` and more than one container network is declared, the `networkNames` array must be defined with the name of the `networks` it's referring to.

- An application can join one or more container mode networks. When joining multiple container networks, there are additional restrictions on port mapping entries (see Port Mappings for details).

- An application can only join one host mode network. This is the default if an app definition does not declare a `networks` field.

- An application can only join one container/bridge network.

- An application cannot mix networking modes: you must either specify a single host network, a single container/bridge network, or one or more container networks.

# Applications

## Container Network (Virtual Network)
The following table summarizes the API transformations when using the network API in Virtual Network Mode:

<table class="table">
  <tbody>
  <tr>
  <th>
    <b>1.4 API</b>
  </th>
  <th>
    <b>1.5 API</b>
  </th>
  </tr>
  <tr>
    <td>
      <pre>
{
    "container": {
      "docker": {
        "image": "image-name",
        "network": "USER",
        "portMappings": [
          {
          "containerPort": 0,
          "name": "port-name"
          }
        ]
      }
    }
    "ipAddress": {
      "networkName": "network-name"
    }
}
    </pre>
  </td>

  <td>
    <pre>
{
    "container": {
      "docker": {
        "image": "image-name"
      },
      "portMappings": [
        {
          "containerPort": 0,
          "name": "port-name"
        }
      ]
    },
    "networks": [
        {
            "mode": "container",
            "name": "network-name"
        }
    ]
}
      </pre>
    </td>
  </tr>
  </tbody>
</table>

**Changes from 1.4 to 1.5**

- `ipAddresses` : Removed.
- `container.docker.network` : Removed.
- `container.docker.portMappings` : Moved to `container.portMappings`.
- `networks` : Added.
- `networks[x].mode` : Is `container` for virtual network.

### Important Notes

**Breaking Feature:** Starting from the 1.5 API, you can specify multiple `container` networks. There are a few things to consider in this case:

- An application can join only join multiple networks using the Mesos containerizer (`MESOS`). Although Docker itself supports multiple networks per container, the Docker containerizer implementation within Mesos does not support multiple networks.

- An application cannot mix networking modes: you must specify a single `host` network, a single `container/bridge` network, or one or more `container` networks.

The following table summarizes the changes when using single or multiple networks:

<table class="table">
  <tbody>
  <tr>
  <th>
    <b>1.5 API (Single Network)</b>
  </th>
  <th>
    <b>1.5 API (Multiple Networks)</b>
  </th>
  </tr>
  <tr>
    <td>
<pre>
{
  "container": {
    "portMappings": [
      {
        "containerPort": 0,
        "name": "port-name"
      }
    ]
  },
  "networks": [
    {
      "mode": "container",
      "name": "network-name-1"
    }
  ]
}
</pre>
</td>

<td>
  <pre>
{
    "container": {
      "portMappings": [
        {
          "containerPort": 0,
          "name": "a-port-name",
          "networkNames": ["network-name-1"]
        },
        {
          "containerPort": 0,
          "name": "another-port-name",
          "networkNames": ["network-name-2"]
        }
      ]
    },
    "networks": [
        {
            "mode": "container",
            "name": "network-name-1"
        },
        {
            "mode": "container",
            "name": "network-name-2"
        }
    ]
}
</pre>
</td>
</tr>
</tbody>
</table>

## Bridge Network

The following table summarizes the API transformations when using the network API in Bridge Mode:

<table class="table">
  <tbody>
  <tr>
  <th>
    <b>1.4 API</b>
  </th>
  <th>
    <b>1.5 API</b>
  </th>
  </tr>
  <tr>
    <td>
      <pre>
{
    "container": {
        "docker": {
            "image": "image-name",
            "network": "BRIDGE",
            "portMappings": [
              {
                "containerPort": 0,
                "hostPort": 0,
                "name": "port-name"
              }
            ]
        }
    }
    "ipAddress": {
        "networkName": "network-name"
    }
}

</pre>
</td>

<td>
 <pre>
 {
    "container": {
      "docker": {
        "image": "image-name"
      },
      "portMappings": [
        {
          "containerPort": 0,
          "hostPort": 0,
          "name": "port-name"
        }
      ]
    },
    "networks": [
        {
            "mode": "container/bridge"
        }
    ]
}
</pre>
</td>
</tr>
</tbody>
</table>

**Changes from 1.4 to 1.5**

- `ipAddresses` : Removed.
- `container.docker.network` : Removed.
- `container.docker.portMappings` : Moved to `container.portMappings`.
- `networks` : Added.
- `networks[x].mode` : Is `container/bridge` for bridge network.

### Important Notes

- An application cannot use more than one `container/bridge` network.
- An application cannot mix networking modes: you must specify a single `host` network, a single `container/bridge` network, or one or more `container` networks.


## Host Network

The following table summarizes the API transformations when using the network API in Host Mode:

<table class="table">
  <tbody>
  <tr>
  <th>
    <b>1.4 API</b>
  </th>
  <th>
    <b>1.5 API</b>
  </th>
  </tr>
  <tr>
    <td>
      <pre>
{
    "container": {
        "type": "DOCKER",
        "docker": {
            "image": "image-name",
            "network": "HOST"
        }
    }
    "portDefinitions": [
        {
            "name": "port-name",
            "protocol" ...,
            "port": ...
        }
    ]
}
</pre>
</td>

<td>
 <pre>
 {
    "container": {
        "type": "DOCKER",
        "docker": {
            "image": "image-name"
        }
    },
    "networks": [
        {
            "mode": "host",
        }
    ],
    "portDefinitions": [
        {
            "name": "port-name",
            "protocol" ...,
            "port": ...
        }
    ]
}
</pre>
</td>
</tr>
</tbody>
</table>

**Changes from 1.4 to 1.5**

- `container.docker.network` : Removed.
- `portDefinitions` : Remains the same.
- `networks` : Added. Optional (in this instance). Default is host mode.
- `networks[x].mode` : Is `host` for Bridge Network.

### Important Notes

- An application cannot use more than one `host` networks.
- An application cannot mix networking modes: You must specify a single `host` network, a single `container/bridge` network, or one or more `container` networks.


## Example Definitions

### Valid Definitions

#### App with container network

The following definitions WILL be accepted by the new API.

<table class="table">
  <tbody>
  <tr>
  <th>
    <b>1.4 API</b>
  </th>
  <th>
    <b>1.5 API</b>
  </th>
  </tr>
  <tr>
    <td>
      <pre>
{
  "id": "user-net-old",
  "cpus": 1,
  "mem": 128,
  "instances": 1,
  "container": {
    "type": "DOCKER",
    "docker": {
      "network": "USER",
      "image": "nginx",
      "portMappings": [
        {
          "containerPort": 80,
          "protocol": "tcp",
          "name": "web"
        }
      ]
    }
  },
  "ipAddress": {
    "networkName": "dcos"
  }
}
</pre>
</td>

<td>
 <pre>
 {
  "id": "user-net-new",
  "cpus": 1,
  "mem": 128,
  "instances": 1,
  "container": {
    "type": "DOCKER",
    "docker": {
      "image": "nginx"
    },
    "portMappings": [
      {
        "containerPort": 80,
        "protocol": "tcp",
        "name": "web"
      }
    ]
  },
  "networks": [
    {
      "mode": "container",
      "name": "dcos"
    }
  ]
}
</pre>
</td>
</tr>
</tbody>
</table>

#### App with multiple container networks

The following definition is now possible with the new API.

<table class="table">
  <tbody>
  <tr>
  <th>
    <b>1.4 API</b>
  </th>
  <th>
    <b>1.5 API</b>
  </th>
  </tr>
  <tr>
    <td>
      <pre>
//not supported
      </pre>
</td>

<td>
 <pre>
 {
  "id": "user-net-multi",
  "cpus": 1,
  "mem": 128,
  "instances": 1,
  "container": {
    "type": "MESOS",
    "docker": {
      "image": "nginx"
    },
    "portMappings": [
      {
        "containerPort": 80,
        "protocol": "tcp",
        "name": "web",
        "networkNames": ["first"]
      },
      {
        "containerPort": 81,
        "protocol": "tcp",
        "name": "admin",
        "networkNames": ["second"]
      }
    ]
  },
  "networks": [
    {
      "mode": "container",
      "name": "first"
    },
    {
      "mode": "container",
      "name": "second"
    }
  ]
}
</pre>
</td>
</tr>
</tbody>
</table>

#### App with bridge network

<table class="table">
  <tbody>
  <tr>
  <th>
    <b>1.4 API</b>
  </th>
  <th>
    <b>1.5 API</b>
  </th>
  </tr>
  <tr>
    <td>
      <pre>
{
  "id": "bridge-net-old",
  "cpus": 1,
  "mem": 128,
  "instances": 1,
  "container": {
    "type": "DOCKER",
    "docker": {
      "image": "nginx",
      "network": "BRIDGE",
      "portMappings": [
        {
          "containerPort": 80,
          "hostPort": 0,
          "protocol": "tcp",
          "name": "web"        }
      ]
    }
  }
}
</pre>
</td>

<td>
 <pre>
 {
  "id": "bridge-net-new",
  "cpus": 1,
  "mem": 128,
  "instances": 1,
  "container": {
    "type": "DOCKER",
    "docker": {
      "image": "nginx"
    },
    "portMappings": [
      {
        "containerPort": 80,
        "hostPort": 0,
        "protocol": "tcp",
        "name": "web"
      }
    ]
  },
  "networks": [
    {
      "mode": "container/bridge"
    }
  ]
}
</pre>
</td>
</tr>
</tbody>
</table>

#### App with host network

<table class="table">
  <tbody>
  <tr>
  <th>
    <b>1.4 API</b>
  </th>
  <th>
    <b>1.5 API</b>
  </th>
  </tr>
  <tr>
    <td>
      <pre>
{
  "id": "host-net-old",
  "cpus": 1,
  "mem": 128,
  "instances": 1,
  "container": {
    "type": "DOCKER",
    "docker": {
      "image": "nginx",
      "network": "HOST"
    }
  },
  "portDefinitions": [
    {
      "port": 0,
      "protocol": "tcp",
      "name": "web"
    }
  ]
}
</pre>
</td>

<td>
 <pre>
 {
  "id": "host-net-new",
  "cpus": 1,
  "mem": 128,
  "instances": 1,
  "container": {
    "type": "DOCKER",
    "docker": {
      "image": "nginx"
    }
  },
  "portDefinitions": [
    {
      "port": 0,
      "protocol": "tcp",
      "name": "web"
    }
  ],
  "networks": [
    {
      "mode": "host"
    }
  ]
}
</pre>
</td>
</tr>
</tbody>
</table>

### Invalid Definitions

#### Mixed Networks (Invalid)
You cannot combine network types.

<table class="table">
  <tbody>
  <tr>
  <th>
    <b>1.5 API</b>
  </th>
  </tr>
  <tr>
    <td>
      <pre>
{
  "id": "x-mixed-networks",
  "cpus": 1,
  "mem": 128,
  "instances": 1,
  "container": {
    "type": "DOCKER",
    "docker": {
      "image": "nginx"
    }
  },
  "portDefinitions": [
    {
      "port": 0,
      "protocol": "tcp",
      "name": "web"
    }
  ],
  "networks": [ // Invalid because network modes cannot be mixed. Only multiple container networks are allowed.
    { "mode": "host" },
    { "mode": "container/bridge" }
  ]
}
</pre>
</td>
</tr>
</tbody>
</table>


#### Missing container network name (Invalid)

You must supply a `name` property to use a container network unless you set the `default_network_name` flag when configuring Marathon. [Learn more about command line flags](http://mesosphere.github.io/marathon/docs/command-line-flags.html).

<table class="table">
  <tbody>
  <tr>
  <th>
    <b>1.5 API</b>
  </th>
  </tr>
  <tr>
    <td>
      <pre>
{
  "id": "x-missing-name",
  "cpus": 1,
  "mem": 128,
  "instances": 1,
  "container": {
    "type": "DOCKER",
    "docker": {
      "image": "nginx"
    }
  },
  "portDefinitions": [
    {
      "port": 0,
      "protocol": "tcp",
      "name": "web"
    }
  ],
  "networks": [ // Possibly invalid because the `networks[x].name` parameter is not supplied
    { "mode": "container" }
  ]
}
</pre>
</td>
</tr>
</tbody>
</table>
