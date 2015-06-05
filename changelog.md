## Changes from 0.8.2 to 0.9.0

#### Recommended Mesos version is 0.22.1

We tested this release against Mesos version 0.22.1. Thus this is the recommended
Mesos version for this release.

#### Disk resource limits are passed to Mesos

If you specify a non-zero disk resource limit, this limit is now passed to Mesos
on task launch.

If you rely on disk limits, you also need to configure Mesos appropriately. This includes configuring the correct
isolator and enabling disk quotas enforcement with `--enforce_container_disk_quota`.

#### New format for the `http_endpoints` parameter

We changed the format of the `http_endpoints` parameters from a
space-separated to a comma-separated list of endpoints, in order to be
more consistent with the documentation and with the format used in other
parameters.

WARNING: If you use the `http_endpoints` parameter with multiple space
separated URLs, you will need to migrate to the comma-separated format.

## Changes from 0.8.1 to 0.8.2

#### New health check option `ignoreHttp1xx`

When set to true, the health check for the given app will ignore HTTP
response codes 100 to 199, in contrast to considering it as unhealthy. With this unbounded task startup times can be handled: the tasks are neither
healthy nor unhealthy as long as e.g. "100 - continue" is returned.

#### HTTPS support for health checks

Health checks now work with HTTPS.

#### Faster (configurable) task distribution

Mesos frequently sends resource offers to Marathon (and all other frameworks). Each offer will represent the available resources of a single node in the cluster. Until now, Marathon would only start a single task per resource offer, which led to slow task launching in smaller clusters. In order to speed up task launching and use the resource offers Marathon receives from Mesos more efficiently, we added a new offer matching algorithm, that tries to start as many tasks as possible per task offer cycle. The maximum numbers of tasks to start are configurable with the following startup parameters:

`--max_tasks_per_offer` (default 1): The maximum number of tasks to start on a single offer per cycle

`--max_tasks_per_offer_cycle` (default 1000): The maximum number of tasks to start in total per cycle

**Example**

Given a cluster with 200 nodes and the default settings for task launching. If we want to start 2000 tasks, it would take at least 10 cycles, because we are only starting 1 task per offer, leading to a total maximum of 200. If we change the `max_tasks_per_offer` setting to 10, we could start 1000 tasks per offer (the default setting for `max_tasks_per_offer_cycle`), reducing the necessary cycles to 2. If we also adjust the `max_tasks_per_offer_cycle ` to 2000, we could start all tasks in a single cycle (given we receive offers for all nodes).

**Important**

Starting too many tasks at once can lead to a higher number of status updates being sent to Marathon than it can currently handle. We will improve the number of events Marathon can handle in a future version. A maximum of 1000 tasks has proven to be a good default for now. `max_tasks_per_offer ` should be adjusted so that `NUM_MESOS_SLAVES * max_tasks_per_offer == max_tasks_per_offer_cycle `. E.g. in a cluster of 200 nodes it should be set to 5.

#### Security settings configurable through env variables

Security settings are now configurable through the following environment variables:

`$MESOSPHERE_HTTP_CREDENTIALS` for HTTP authentication (e.g. `export MESOSPHERE_HTTP_CREDENTIALS=user:password`)

`$MESOSPHERE_KEYSTORE_PATH` + `$MESOSPHERE_KEYSTORE_PASS` for SSL settings

#### Isolated deployment rollbacks

Marathon allows rolling back running deployments via the [DELETE /v2/deployments/{deploymentId}](https://mesosphere.github.io/marathon/docs/rest-api.html#delete-/v2/deployments/%7Bdeploymentid%7D) command or the "rollback" button in the GUI.

In prior Marathon versions, deployment rollbacks reverted all applications to the state before the selected deployment. If you performed concurrent deployments, these would also be reverted.

Now Marathon isolates the changes of the selected deployment and calculates a deployment plan which prevents changing unrelated apps.

#### Empty groups can be overwritten by apps

Instead of declining the creation of an app with the same name as a previously existing group, the group will now be removed if empty and replaced with the app.

#### Performance improvements

App and task related API calls should be considerably faster with large amounts of tasks now.

## Changes from 0.8.0 to 0.8.1

#### New option `dryRun` on endpoint `PUT /v2/groups/{id}`

When sending a group definition to this endpoint with `dryRun=true`,
it will return the deployment steps Marathon would execute to deploy
this group.

#### New endpoint POST `/v2/tasks/delete`

Takes a JSON object containing an array of task ids and kills them.
If `?scale=true` the tasks will not be restarted and the `instances`
field of the affected apps will be adjusted.

#### POST `/v2/apps` rejects existing ids

If an app with an already existing id is posted to this endpoint,
it will now be rejected

#### PUT `/v2/apps/{id}` always returns deployment info

In versions <= 0.8.0 it used to return the complete app definition
if the resource didn't exist before. To be consistent in the response,
it has been changed to always return the deployment info instead. However
it still return a `201 - Created` if the resource didn't exist.

#### GET `/v2/queue` includes delay

In 0.8.0 the queueing behavior has changed and the output of this endpoint
did not contain the delay field anymore. In 0.8.1 we re-added this field.

