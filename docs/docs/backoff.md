---
title: No new instances of app are starting, it is in 'delayed' mode
---

# No new instances of app are starting, it is in 'delayed' mode

One of the reasons why is your deployment not finishing and your instances appear to start very slowly is that your application 
might be in 'delayed' mode.

The delayed mode is a Marathon way how to protect the cluster against applications that are crash looping very frequently.
If such application exist on your cluster it might cause all kinds of problems from Marathon overloading
Mesos with launch request to disks filling up on Mesos Agents (due to sandboxes created by failing tasks). To prevent 
that from happening, when Marathon detects and app is failing frequently, it will apply delay to all future launches
so all new instances are created in a slower pace.

## How to find out if my app is being delayed

There are more reasons for application instances not being started. Some of them are described [on this page](waiting.html).
To find out if delay might be causing your instances not to be launched you have to:
- query the `v2/queue` endpoint
- find an item where `app.id` equals id of your application
- if `delay.timeLeftSeconds` property is higher than 0 then your app is delayed

## How is delay configured

`BackoffStrategy` is a property that determines if your app will be delayed and for how long. That's a `backoffFactor`
`maxLaunchDelaySeconds` and `backoffSeconds` on Application definition ([App RAML definition](https://github.com/mesosphere/marathon/blob/master/docs/docs/rest-api/public/api/v2/types/app.raml)). For pods there is an `backoff` object with `backoff`, `backoffFactor` 
and `maxLaunchDelay` properties ([Pod RAML definition](https://github.com/mesosphere/marathon/blob/master/docs/docs/rest-api/public/api/v2/types/pod.raml#L35)).

- `backoffSeconds` - this is the delay that will be applied to an application that has been just created or deployed in new version
- `backoffFactor` - The factor applied to the current backoff to determine the new backoff. 
- `maxLaunchDelaySeconds` - maximal number of delay, default is 5 minutes

When you deploy new application or deploy new version of existing application, the delay value for that application is set to `delaySeconds`.
Every time an instance of this application fails, current value of delay is multiplied by the `backoffFactor` up until
`maxLaunchDelaySeconds` is reached.

The delay is increased also when task fails or exits with exit code 0 (`TASK_FAILED` and `TASK_FINISHED` in Mesos). 

The delay is NOT increased when task is killed (`TASK_KILLED` in Mesos).

## When is the delay reset to default

- when new version of an application is deployed
- when `DELETE /v2/queue/{your_application_name}/delay` HTTP API request is issued
- when there are no failing tasks for the value of current delay + maxLaunchDelay

## How do I reset the delay?

By issuing a request to `DELETE /v2/queue/{your_application_name}/delay`.

