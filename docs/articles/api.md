# API

## Groups

**TODO: we might want to separate out Groups Endpoint, Apps Endpoint, etc, to not have an overwhelmingly huge API page.**

* [Creating groups](#creating-groups)
* [Querying groups](#querying-groups)
* [Updating groups](#updating-groups)
* [Deleting groups](#deleting-groups)
* [Group dependencies](#group-dependencies)
* [Group scaling](#group-scaling)

### Creating groups

**TODO**

### Querying groups

**TODO**

The complete tree structure of current service groups can be fetched using the [v2/groups](api.md#groups) endpoint. For something like the above example, the result would look similar to this:

```
{
    "id": "/",
    "dependencies": [],
    "apps": [
        {
            "id": "/frontend",
            ...
        }
    ],
    "pods": [],
    "groups": [
        {
            "id": "/backend",
            "dependencies": [],
            "apps": [
                {
                    "id": "/backend/cache",
                    ...
                },
                {
                    "id": "/backend/mysql",
                    ...
                },
                {
                    "id": "/backend/service",
                    ...
                }
            ],
            "groups": [],
            "pods": []
        }
    ]
}
```

This is a simplified JSON representation of the service group tree which omits service definition details for brevity. Note that [apps](applications.md) and [pods](pods.md) are held separately (mostly for historic reasons). There is also a `dependencies` field which is explained [below](#group-dependencies).


### Updating groups

**TODO**

### Deleting groups

**TODO**

### Group dependencies

Let's assume that we have a a backend service that needs a database to start. This can be modelled by putting database and backend services in two different groups and defining dependencies between those two:

```
/product
   /database
     /mysql
     /mongo
   /service
     /rails-app
     /play-app

```

All services can be deployed by POSTing a group definition to `/v2/groups` REST API endpoint (concrete service definitions are omitted from this example):

```
{
  "id": "/product",
  "groups": [
    {
      "id": "/product/database",
      "apps": [
         { "id": "/product/database/mongo",
            ...
         },
         { "id": "/product/database/mysql",
            ...
         }
       ]
    },{
      "id": "/product/service",
      "dependencies": ["/product/database"],
      "apps": [
         { "id": "/product/service/rails-app",
            ...
         },
         { "id": "/product/service/play-app",
            ...
         }
      ]
    }
  ]
}
```

Note that `/product/service` group has a defined dependency on `/product/database`. This means that Marathon will deploy all services in the `/product/database` group and wait for them to become ready, before proceeding with deploying any services in the group `/product/service`. In this case, ready is defined as all the corresponding tasks are started, and that they are healthy should health checks be defined.

**Note:** Marathon service group dependencies are "deployment time dependencies", meaning they are respected only during the initial deployment of the services. Should any of the services fail during their respective life-cycle, they are restarted independent of defined dependencies.

### Group scaling

TODO: example with instances: 1 and factor 1.5
A service group and all of its trasitive services can be scaled up or down by a given factor by POSTing following payload to `/v2/groups/{group}` (e.g. `/v2/groups/product/service`):

```
{
   "scaleBy": 2
}
```

This can come in handy when scaling a group of product services due to e.g. change in traffic.
