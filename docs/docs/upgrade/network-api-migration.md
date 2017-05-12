---
title: Migrate to 1.5 Networking API
---

# Overview
The networking section of the Marathon API has changed significantly in version 1.5. Marathon can still accept requests using the 1.4 version of the API, but it will always reply with the 1.5 version of the app definition. This WILL likely break applications that consume networking-related fields of the application definition.

This document contains the high-level structural differences between the 1.4 and 1.5 API versions. You can read more details regarding the networking section and the correct values for every field in the [networking documentation]({{ site.baseurl }}/docs/networking.html).

## Important notes

Marathon now supports applications attached to more than one network. However, additional applies when you specify more than one network:

- If a portMapping has defined a hostPort, the networkNames array must be defined with the name(s) of the networks it's referring to.

- An application can join one or more container mode networks. When joining multiple container networks, there are additional restrictions on port mapping entries (see Port Mappings for details).

- An application can only join one host mode network. This is the default if an app definition does not declare a networks field.

- An application can only join one container/bridge network.

- An application cannot mix networking modes: you must either specify a single host network, a single container/bridge network, or one or more container networks.

# Applications

## Container Network (Virtual Network)
The following table summarizes the API transformations when using the network API in Virtual Network Mode:

| 1.4 API     	                       | 1.5 API     	|
| ------------------------------------ |-------------	|
| ```                                  |```                             |
|  {                                   |{                               |
|    "container": {                    |  "container": {                |
|        "docker": {                   |    "portMappings": [           |
|            "network": "USER",        |      {                         |
|            "portMappings": [         |        "containerPort": 0,     |
|              {                       |        "name": "<port-name>"   |
|                "containerPort": 0,   |      }                         |
|                "name": "<port-name>" |    ]                           |
|              }                       |  },                            |
|            ]                         |  "networks": [                 |
|        }                             |    {                           |
|    }                                 |      "mode": "container",      |
|    "ipAddress": {                    |      "name": "<network-name>"  |
|        "networkName": "<network-name>"  | }                           |
|    }                                 |  ]                             |
|}                                     |}                               |
|``` 	                                 |```	                            |

**Changes from 1.4 to 1.5**

- `ipAddresses` : Removed.
- `container.docker.network` : Removed.
- `container.docker.portMappings` : Moved to `container.portMappings`.
- `networks` : Added.
- `networks[x].mode` : Is “container” for virtual network.

### Important Notes

**Breaking Feature:** Starting from the 1.5 API, you can specify multiple container networks. There are a few things to consider in this case:

- An application can join only join multiple networks using the Mesos containerizer (`MESOS`). Although Docker itself supports multiple networks per container, the Docker containerizer implementation within Mesos does not support multiple networks.

- When more than one network is specified, every `containerPort` MUST include a `networkNames` array with the name(s) of the network(s) it is associated with.

- An application cannot mix networking modes: you must specify a single host network, a single container/bridge network, or one or more container networks.

The following table summarizes the changes when using single or multiple networks:

| 1.5 API (Single Network)              | 1.5 API (Multiple Networks)           |
| ------------------------------------- | ------------------------------------- |
|```                                    |```                                    |
|{                                      |{ “container”: {                       |
|     “container”: {                    |   "portMappings": [                   |
|      "portMappings": [                |     {                                 |
|        {                              |       "containerPort": 0,             |
|          "containerPort": 0,          |       "name": "<a-port-name>",        |
|          "name": "<some port>"        |       “networkNames”: [“<network1>”]  |  
|        }                              |     },                                |
|      ]                                |     {                                 |
|    },                                 |       "containerPort": 0,             |
|    "networks": [                      |       "name": "<another-port-name>",  |
|        {                              |       “networkNames”: [“<network2>”]  |
|            "mode": "container",       |     }                                 |
|            "name": "<network1>"       |   ]                                   |
|        }                              | },                                    |
|    ]                                  | "networks": [                         |
|}                                      |   {                                   |
|```                                    |     "mode": "container",              |
|                                       |     "name": "<network1>"              |
|                                       |   },                                  |
|                                       |   {                                   |
|                                       |     "mode": "container",              |
|                                       |     "name": "<network2>"              |
|                                       |   }                                   |
|                                       |  ]                                    |
|                                       |}                                      |
|                                       |```                                    |

## Bridge Network

The following table summarizes the API transformations when using the network API in Bridge Mode:

| 1.4 API                               | 1.5 API                               |
| ------------------------------------- | ------------------------------------- |
|```                                    |```                                    |  
|{                                      |{                                      |
|    "container": {                     | “container”: {                        |
|        "docker": {                    |   "portMappings": [                   |
|            "network": "BRIDGE",       |     {                                 |
|            "portMappings": [          |       "containerPort": 0,             |
|              {                        |       "hostPort": 0,                  |
|                "containerPort": 0,    |       "name": "<port-name>"           |
|                “hostPort”: 0,         |     }                                 |
|                "name": "<port-name>"  |   ]                                   |
|              }                        | },                                    |
|            ]                          | "networks": [                         |
|        }                              |   {                                   |
|    }                                  |     "mode": "container/bridge"        |
|    "ipAddress": {                     |   }                                   |
|        "networkName": "<some name>"   | ]                                     |
|    }                                  |}                                      |
|}                                      |```                                    |
|'''                                    |                                       |

**Changes from 1.4 to 1.5**

- `ipAddresses` : Removed.
- `container.docker.network` : Removed.
- `container.docker.portMappings` : Moved to `container.portMappings`.
- `networks` : Added.
- `networks[x].mode` : Is “container/bridge” for bridge network.

### Important Notes

- An application cannot use more than one “container/bridge” network.
- An application cannot mix networking modes: you must specify a single host network, a single container/bridge network, or one or more container networks.


## Host Network

The following table summarizes the API transformations when using the network API in Host Mode:

| 1.4 API                               | 1.5 API                               |
| ------------------------------------- | ------------------------------------- |
| ```                                   |```                                    |
|{                                      |{                                      |
|    "container": {                     |    “container”: {                     |
|        “type”: “DOCKER”,              |        “type”: “DOCKER”,              |
|        "docker": {                    |        “docker”: {                    |
|            “image”: “foo”,            |            “image”: “foo”             |
|            "network": "HOST"          |        }                              |
|        }                              |    },                                 |
|    }                                  |    "networks": [                      |
|    "portDefinitions": [               |        {                              |
|        {                              |            "mode": "host",            |
|            "name": "<some name>",     |            "name": "<network-name>"   |
|            "protocol" ...,            |        }                              |
|            "port": ...                |    ],                                 |
|        }                              |    "portDefinitions": [               |
|    ]                                  |        {                              |
|}                                      |            "name": "<port-name>",     |
|```                                    |            "protocol" ...,            |
|                                       |            "port": ...                |
|                                       |        }                              |
|                                       |    ]                                  |
|                                       |}                                      |
|                                       |```                                    |

**Changes from 1.4 to 1.5**

- `container.docker.network` : Removed.
- `portDefinitions` : Remains the same.
- `networks` : Added.
- `networks[x].mode` : Is “host” for Bridge Network.

### Important Notes

- An application cannot use more than one “host” networks.
- An application cannot mix networking modes: You must specify a single host network, a single container/bridge network, or one or more container networks.


## Example Definitions

### Valid Definitions

#### App with container network

The following definitions WILL be accepted by the new API.

| 1.4 API                               | 1.5 API                               |
| ------------------------------------- | ------------------------------------- |
|```                                    |```                                    |
|{                                      |{                                      |
|  "id": "user-net-old",                |  "id": "user-net-new",                |
|  "cpus": 1,                           |  "cpus": 1,                           |
|  "mem": 128,                          |  "mem": 128,                          |
|  "instances": 1,                      |  "instances": 1,                      |
|  "container": {                       |  "container": {                       |
|    "type": "DOCKER",                  |    "type": "DOCKER",                  |
|    "docker": {                        |    "docker": {                        |
|      "network": "USER",               |      "image": "nginx"                 |
|      "image": "nginx",                |    },                                 |
|      "portMappings": [                |    "portMappings": [                  |
|        {                              |      {                                |
|          "containerPort": 80,         |        "containerPort": 80,           |
|          "protocol": "tcp",           |        "protocol": "tcp",             |
|          "name": "web"                |        "name": "web"                  |
|        }                              |      }                                |
|      ]                                |    ]                                  |
|    }                                  |  },                                   |
|  },                                   |  "networks": [                        |
|  "ipAddress": {                       |    {                                  |
|    "networkName": "dcos"              |      "mode": "container",             |
|  }                                    |      "name": "dcos"                   |
|}                                      |    }                                  |
|```                                    |  ]                                    |
|                                       |}                                      |
|                                       |```                                    |

#### App with multiple container networks

The following definition is now possible with the new API.

| 1.4 API             | 1.5 API                              |
| ------------------ | ------------------------------------- |
| // Not supported.  |```                                    |
|                    |{                                      |
|                    |  "id": "user-net-multi",              |
|                    |  "cpus": 1,                           |
|                    |  "mem": 128,                          |
|                    |  "instances": 1,                      |
|                    |  "container": {                       |
|                    |    "type": "MESOS",                   |
|                    |    "docker": {                        |
|                    |      "image": "nginx"                 |
|                    |    },                                 |
|                    |    "portMappings": [                  |
|                    |      {                                |
|                    |        "containerPort": 80,           |
|                    |        "protocol": "tcp",             |
|                    |        "name": "web",                 |
|                    |        "networkNames": ["first"]      |
|                    |      },                               |
|                    |      {                                |
|                    |        "containerPort": 81,           |
|                    |        "protocol": "tcp",             |
|                    |        "name": "admin",               |
|                    |        "networkNames": ["second"]     |
|                    |      }                                |
|                    |    ]                                  |
|                    |  },                                   |
|                    |  "networks": [                        |
|                    |    {                                  |
|                    |      "mode": "container",             |
|                    |      "name": "first"                  |
|                    |    },                                 |
|                    |    {                                  |
|                    |      "mode": "container",             |
|                    |      "name": "second"                 |
|                    |     }                                 |
|                    |  ]                                    |
|                    |}                                      |
|                    |```                                    |

#### App with bridge network

| 1.4 API                       | 1.5 API                              |
| ----------------------------- |------------------------------------- |
|```                            |```                                   |
|{                              |{                                     |
|  "id": "bridge-net-old",      |  "id": "bridge-net-new",             |
|  "cpus": 1,                   |  "cpus": 1,                          |
|  "mem": 128,                  |  "mem": 128,                         |
|  "instances": 1,              |  "instances": 1,                     |
|  "container": {               |  "container": {                      |
|    "type": "DOCKER",          |    "type": "DOCKER",                 |
|    "docker": {                |    "docker": {                       |
|      "image": "nginx",        |      "image": "nginx"                |
|      "network": "BRIDGE",     |    },                                |
|      "portMappings": [        |    "portMappings": [                 |
|        {                      |      {                               |
|          "containerPort": 80, |        "containerPort": 80,          |
|          “hostPort”: 0,       |        "hostPort": 0,                |
|          "protocol": "tcp",   |        "protocol": "tcp",            |
|         "name": "web"        }|        "name": "web"                 |
|      ]                        |      }                               |
|    }                          |    ]                                 |
|  }                            |  },                                  |
|}                              |  "networks": [                       |
|```                            |    {                                 |
|                               |      "mode": "container/bridge"      |
|                               |    }                                 |
|                               |  ]                                   |
|                               |}                                     |
|                               |```                                   |

C.1.4. App with host network

1.4 API
1.5 API
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




C.2. Invalid Definitions
C.2.1. Mixed Networks (Invalid)
It’s not possible to mix network types

1.5 API
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
  "networks": [
    { "mode": "host" },
    { "mode": "container/bridge" }
  ]
}



C.2.2. Missing container network name (Invalid)
It’s not possible to use a container network without giving a “name” property

1.5 API
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
  "networks": [
    { "mode": "container" }
  ]
}
