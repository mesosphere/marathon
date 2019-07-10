# Deployments

## App Deployments

## Group Deployments

### Caveats

When you create, upgrade or remove a set of applications via the groups endpoint, a multi-step deployment is triggered. The upside of this is, that you don't need to deploy each of these individually. However, in case of failures, you need to be aware of some things:

*Stopping Group Deployments*
When you stop a multi-step deployment via `DELETE /v2/deployments/$deploymentId`, each step is canceled and left in the state it is currently in. Consider the following example, where there are two apps, `backend` and `frontend`, each with 3 instances in version 1: 

```
/dev
  /backend
    tasks:
      backend-a1
      backend-b1
      backend-c1
  /frontend
    tasks:
      frontend-a1
      frontend-b1
      frontend-c1
```

If you upgrade both apps to a new image in one groups deployment, both of them will be upgraded in parallel.

TODO:
upgrade strategy both ~ minHealthy = 1
Marathon will launch 1 new of each
backend needs ~ 10 min to turn ready/healthy
frontend fails immediately
try to fix frontend
  upgrade app will be rejected b/c app is locked
  forcing will upgrade app but leave backend stale
  forcing group update w/o backend provided will remove backend
  forcing group update has to provide backend in the right target version
  ?? will force upgrade keep already launched backend task?

## Pod Deployments
