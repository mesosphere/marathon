# Service Groups

## What is a service group?

Service groups are used to contain [services](services.md) that semantically belong together. Groups are inferred from a service's ID, which designates a path.

If you create two services, an [app](applications.md) with ID `/backend/cache` and a [pod](pods.md) with ID `/backend/service`, both services will be located within the group `backend`, while `backend` itself is is located underneath the root group `/`. Both `/` and `backend` will be created implicitly once the first one of these services is created; i.e. you don't have to create either of them explicitly before you can add services. A service called `/test` is simply located within the root `/`. If you would create all these mentioned services, resulting service group tree would look like following:

```
/
  frontend
  backend/
   cache
   service
```

Note that while `frontend` is a service, `backend` is merely a group holding its children together. Services and groups may not share names; in this example, you could not also have a service named `backend`, and you could not have a group called `frontend` within the root.

In a bigger organization, where a cluster is shared between different departments, a structure normally looks something like `/$department/$project/$service-group$/$service` (e.g. `/engineering/shop/backend/database`).

## Implications

### DNS

A service's DNS name will be resolved using its full path. That means, groups are reflected within your service's networking addresses. In order to do this, the hostname for instances of a service will be populated using its path. For a service `/dev/backend/server`, the advertised hostname will be `server.backend.dev`.  

### Permissions

Permissions are resolved based on groups via Access Control Lists in DC/OS. That means that you can restrict access to services inside a folder to people that have access to that group. Note that this requires a plugin to be used with Marathon, which is not publicly available at this point.

// TODO: link to permissions in DC/OS

## How can I use service groups?

Service groups are mainly an implementation detail, but you can operate on this model to a certain extent. The functionality of service groups goes beyond simple grouping, however all of the advanced features should be used with caution. At a glance, the [v2/groups](api.md#groups) endpoint allows you to

* [read](api.md#querying-groups) both apps and pods
* [create](api.md#creating-groups), [update](api.md#updating-groups) and [delete](api.md#deleting-groups) one or [multiple](deployments.md#group-deployments) apps at once, including apps in nested groups
* deploy multiple apps with [dependencies](api.md#group-dependencies)
* [scale](api.md#group-scaling) all apps inside a group by a factor

However, you *cannot*

* create, update or delete pods
* deploy multiple pods at once
* deploy multiple apps with dependencies
* scale pods inside a group by a factor

## Important caveats

**TODO**

### You cannot create pods via the groups endpoint

**TODO**

### Groups don't support PATCH operations

The groups endpoint provides http POST and PUT operations. POST can only be used ot create groups that do not exist, while PUT operations replace an existing group with the provided structure – in contrast to the default for PUT on apps, which has a PATCH behavior. That means

* apps will be replaced with the configuration as provided in the request payload.
* apps not contained in the request payload *will be removed*.
* groups not contained in the request payload *will be removed* transitively, including any apps contained within them.
* any pods within the provided structure will be left untouched, since pods [cannot be modified](#you-cannot-create-pods-via-the-groups-endpoint) using the groups endpoint. 

For the sake of example, say you have the following existing services:

```
/product/service
  /rails-app
  /play-app
```

If you PUT the following service group definition to the `/v2/groups` REST endpoint, then `/product/service/rails-app` will be removed:

```
{
  "id": "/product/service",
  "dependencies": ["/product/database"],
  "apps": [
    { "id": "/product/service/play-app",
      ...
    }
  ]
}
```

## Links

* [Groups API](api.md#groups)
* [Services](services.md)
* [Apps](applications.md)
* [Pods](pods.md)
* [Deployments](deployments.md)
