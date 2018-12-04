# Application groups

## What is an application group?
 Marathon keeps all its [services](services.md) in a tree-like structure called application group. Let's consider a few existing services e.g. `/frontend`, `/backend/mysql`, `/backend/cache` and `/backend/service`. The resulting application group will look like following:

```
/frontend
/backend
   /cache
   /mysql
   /service
```
where e.g. `/frontend` exists in the tree root `/` (also refered as "root group") while e.g. `/cache` is an service in the group `/backend`. Note that while `/frontend` is an actual service (with existing service definition), `/backend` is merely a group holding its children applications together. You can **either** a group **or** a service
definition with the same name but not both.

Current application groups can be fetched using Rest API [v2/groups](api.md) endpoint. For the above example the result would look something like:
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
This is a JSON representation of the application group tree. Service definitions details are omitted for brevity. Note that [apps](apps.md) and [pods](pods.md) are held separately (mostly for historic reasons). There is also a `dependency` field which is explained below.

## How can I use application groups?
Similar to fodlers that are used to group related files together, application groups can be used to group similar services. In a bigger organisation, where a production cluster is shared between different departments a structure can looks something like `/$department/$project/$service-group$/$service` e.g. `/engineering/shop/backend/database`. However, the advantages of the application groups go beyond simple grouping:

- Group deployment: an application group (and all it's children recursively) can be deployed and removed together using one API request. For more information on this topic see [deployments](deployments.md)
- All services within the group can by [scaled](scaling.md) up or down by some factor (see below)
- Groups can have dependencies on each other (see below)

### Group Dependencies
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

All services can be deployed by POSTing following json definition to `/v2/groups` REST API endpoint (concrete service definition omitted):
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
Note that `/product/service` group had a defined dependency on `/product/database`. This means that Marathon will deploy the databases first (start the corresponding tasks, wait for them to become healthy should health checks be defined) before proceeding with services.

**Note:** Marathon group dependencies are "deployment time dependencies" meaning they are respected only during the initial deployment of the services. Should any of the services fail during their respective life-cycle they are restarted independent of defined dependecies.

### Group scaling
A group and all its trasitive services can be scaled up or down by a given factor by POSTing following json definition to e.g. `/v2/groups/product/service`:
```
{
   "scaleBy": 2
}
```
This can come in handy when scaling a group of product services due to e.g. change in traffic.

## Are there any limitations or things to consider?
**Note:** when deploying an application group it will replace **all** existing services for the given path transitively e.g. when deploying new appliation group `/product/service` all existing services in `/product/service` (and their children) will be removed and replaced with the new ones.

## Links
* [Deployments](deployments.md)
* [Services](services.md)
* [Apps](apps.md)
* [Pods](pods.md)
* [Rest API](api.md)