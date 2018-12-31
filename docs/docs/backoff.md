---
title: No new instances of app are starting, it is in 'delayed' mode
---

# New instances of app are not starting because it is in 'delayed' mode

One of the reasons your deployment is not finishing and / or your instances appear to start very slowly is that your application might be in 'delayed' mode.

The delayed mode in Marathon is a way how to protect the cluster against applications that are crash looping frequently.
If such situation exists on your cluster it might cause all kinds of problems from Marathon overloading
Mesos with launch request to disks filling up on Mesos Agents (due to sandboxes created by failing tasks). To prevent
that from happening, when Marathon detects an app is failing frequently, it will apply a delay to all future launches
so all new instances are created in a slower pace.  The length of time of the delay is incremented based on an incremental backoff delay, increasing the time of the delay to the configured limit.

## How to find out if my app is being delayed

There are a number of [reasons for application instances not being started](waiting.html).
To find out if the backoff delay might be causing your instances not to be launched you need to:
- query the `v2/queue` endpoint
- find an item where `app.id` equals id of your application
- if `delay.timeLeftSeconds` property is higher than 0 then your app is delayed

## How to configure the backoff delay

`BackoffStrategy` is a property that determines if your app will be delayed and for how long.  The Application definition ([App RAML definition](https://github.com/mesosphere/marathon/blob/master/docs/docs/rest-api/public/api/v2/types/app.raml)) includes a `backoffFactor`, `maxLaunchDelaySeconds` and `backoffSeconds`. For pods there is an `backoff` object with `backoff`, `backoffFactor` and `maxLaunchDelay` properties ([Pod RAML definition](https://github.com/mesosphere/marathon/blob/master/docs/docs/rest-api/public/api/v2/types/pod.raml#L35)).

- `backoffSeconds` - this is the delay that will be applied to an application that has been just created or deployed in new version
- `backoffFactor` - The factor applied to the current backoff to determine the new backoff.
- `maxLaunchDelaySeconds` - maximal number of delay, default is 5 minutes

When deploying a new application or a new version of existing application, the delay value for that application is set to `delaySeconds`.
Every time an instance of this application fails, the current value of delay is multiplied by the `backoffFactor` up until
`maxLaunchDelaySeconds` is reached.

The delay is increased also when task fails or exits with exit code 0 (`TASK_FAILED` and `TASK_FINISHED` in Mesos).

The delay is NOT increased when task is killed (`TASK_KILLED` in Mesos).

## When is the delay reset to default

- when a new version of an application is deployed
- when `DELETE /v2/queue/{app.id}/delay` HTTP API request is issued
- when there are no failing tasks for the value of current delay + maxLaunchDelay

## How do I reset the delay?

By issuing a request to `DELETE /v2/queue/{app.id}/delay`.
