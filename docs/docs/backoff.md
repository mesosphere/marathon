---
title: No new instances of a service are starting, it is in 'delayed' mode
---

# New instances of service are not starting because it is delayed

If your deployment is not finishing and/or your instances appear to start very slowly, your service may be delayed.

In order to protect the cluster from being overloaded with launch requests, Marathon will slow down the launch rate of services that crash loop / fail frequently via a mechanism known as "backoff delay".
Were it not for this protective measure, Marathon could cause cluster-wide agents due to disks filling up on Mesos Agents with sandboxes created for the frequently-launched-and-failing failing tasks.

## How do I find out if my service is being delayed?

There are a number of [reasons for application instances not being started](waiting.html).
To find out if the backoff delay might be causing your instances not to be launched you need to:

1. Query the `v2/queue` endpoint.
2. Find an item where `service.id` equals id of your application or pod.
3. Read the property `delay.timeLeftSeconds`; if it is higher than 0, then your service is delayed.

## How do I configure the backoff delay?

The backoff delay length and rate can be configured per service. For apps, the [Application definition](https://github.com/mesosphere/marathon/blob/master/docs/docs/rest-api/public/api/v2/types/app.raml) has the properties `backoffFactor`, `maxLaunchDelaySeconds` and `backoffSeconds`. For pods, the [Pod definition](https://github.com/mesosphere/marathon/blob/master/docs/docs/rest-api/public/api/v2/types/pod.raml#L35) has a `backoff` object with the properties `backoff`, `backoffFactor` and `maxLaunchDelay`.

- `backoffSeconds` - The initial delay applied to a service that has failed for the first time.
- `backoffFactor` - Controls the rate at which the backoff delay grows; a value of 1.05 would result in a 5% slower launch rate after each failure.
- `maxLaunchDelaySeconds` - The largest delay allowed (default: 5 minutes).

When deploying a new service or a new version of existing service, the delay value for that application is reset to `delaySeconds`.
Every time an instance of this application fails, the current value of delay is multiplied by the `backoffFactor` up until `maxLaunchDelaySeconds` is reached.

The delay is increased also when task fails or exits with exit code 0 (`TASK_FAILED` and `TASK_FINISHED` in Mesos).

The delay is NOT increased when task is killed (`TASK_KILLED` in Mesos).

## When is the delay reset to default?

- When a new version of a service is deployed
- When `DELETE /v2/queue/{app.id}/delay` HTTP API request is issued
- When there are no failing tasks for the value of current delay + maxLaunchDelay

## How do I reset the delay?

By issuing a request to `DELETE /v2/queue/{service.id}/delay`.
