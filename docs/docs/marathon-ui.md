---
title: Marathon UI
---

# Marathon UI

The **Marathon UI** source code repository lives at [https://github.com/mesosphere/marathon-ui](https://github.com/mesosphere/marathon-ui).
For issues and feature requests related to the web UI, please refer to the [Support]({{ site.baseurl }}/support.html) page.

<img src="{{ site.baseurl }}/img/marathon-ui-0.13.png" width="700" alt="Marathon UI in 0.13">

By default, Marathon exposes its web UI on port `8080`. This can be configured
via [command line flags]({{ site.baseurl }}/command-line-flags.html).

## Application Status Reference
Marathon UI introduces the following concepts to illustrate the possible statuses
an app can be in at any point in time:

- **Running**
- **Deploying**
- **Suspended**
- **Delayed**
- **Waiting**

These statuses are displayed in the UI's application list view to provide an
at-a-glance overview of the global state to the user.

Following is an explanation of how each status is determined based on certain
conditions as exposed by the REST API.

-----------

#### Running
The application is reported as being successfully running.

-----------

#### Deploying
Whenever a change to the application has been requested by the user. Marathon is
performing the required actions, which haven't completed yet.
True when the `v2/apps` endpoint returns a JSON response with
`app.deployments.length > 0`.

-----------

#### Suspended
An application with a target instances of 0 and whose running tasks count is 0.
True when the JSON returned looks like:
`app.instances === 0 && app.tasksRunning === 0`

-----------

Additionally, by inspecting the `v2/queue` endpoint the following states can
also be determined:

#### Waiting
Marathon is waiting for offers from Mesos. True whenever an app has
`queueEntry.delay.overdue === true`.

-----------

#### Delayed
An app is considered `delayed` whenever too many tasks of the application failed
 in a short amount of time. Marathon will pause this deployment and retry later.
True if the `queue` endpoint returns the
following JSON conditions:
`queueEntry.delay.overdue === false && queueEntry.delay.timeLeftSeconds > 0`

-----------


