---
title: Blue-Green Deployment
---

# Blue-Green Deployment

Blue-green deployment is a way to safely deploy applications that are serving live traffic by creating two versions of an application (BLUE and GREEN). To deploy a new version of the application, you will drain all traffic, requests, and pending operations from the current version of the application, switch to the new version, and then turn off the old version. Blue-green deployment eliminates application downtime and allows you to quickly roll back to the BLUE version of the application if necessary.

For an overview of the process, here's [a great article by Martin Fowler](http://martinfowler.com/bliki/BlueGreenDeployment.html).

In a production environment, you would typically script this process and integrate it into your existing deployment system. Below, we provide an example of the steps necessary to perform a safe deployment using the DCOS CLI. (The DCOS CLI works with both DCOS and open source Marathon.)

## Requirements

- A Marathon-based app with health checks that accurately reflect the health of the application.
- The app must expose a metric endpoint to determine whether the app has any pending operations. For example, the application could expose a global atomic gauge of the number of currently queued DB transactions.
- The [jq] (https://stedolan.github.io/jq/) command-line JSON processor. 
- If you are using open source Mesos, [configure the DCOS CLI] ( https://github.com/mesosphere/dcos-cli#using-the-cli-without-dcos).

## Procedure

We will replace the current app version (BLUE) with a new version (GREEN).

1. Launch the new version of the app on Marathon. Add a unique ID to the app name, such as the Git commit ID. In this example, we ID the new version of the app by adding `GREEN` to its name.

    ```sh
    # launch green
    dcos marathon app add green-myapp.json
    ```
**Note:** If you were using the API instead of the DCOS CLI, the command above would be much longer:

    ```sh
    curl -H "Content-Type: application/json" -X POST -d @green-myapp.json <hosturl>/marathon/v2/apps
    ```

2. Scale GREEN app instances by 1 or more. Initially (starting from 0 instances), set the number of app instances to the minimum required to serve traffic. Remember, no traffic will arrive yet: we haven't registered at the load balancer.

    ```sh
    # scale green
    dcos marathon app update /green-myapp instances=1
    ```

3. Wait until all tasks from the GREEN app have passed health checks. This step requires [jq] (https://stedolan.github.io/jq/).

    ```sh
    # wait until healthy
    dcos marathon app show /green-myapp | jq '.tasks[].healthCheckResults[] | select (.alive == false)'
    ```

4. Use the code snippet above to check that all instances of GREEN are still healthy. Abort the deployment and begin rollback if you see unexpected behavior.

5. Add the new task instances from the GREEN app to the load balancer pool.

6. Pick one or more task instances from the current (BLUE) version of the app.

    ```sh
    # pick tasks from blue
    dcos marathon task list /blue-myapp
    ```

7. Update the load balancer configuration to remove the task instances above from the BLUE app pool.

8. Wait until the task instances from the BLUE app have 0 pending operations. Use the metrics endpoint in the application to determine the number of pending operations.

9. Once all operations are complete from the BLUE tasks, kill and scale the BLUE app using [the API] (https://mesosphere.github.io/marathon/docs/rest-api.html#post-v2-tasks-delete). In the snippet below, ``<hosturl>`` is the hostname of your master node prefixed with ``http://``.

    ```sh
    # kill and scale blue tasks
    echo "{\"ids\":[\"<task_id>\"]}" | curl -H "Content-Type: application/json" -X POST -d @- <hosturl>/marathon/v2/tasks/delete?scale=true
    ```

    This Marathon operation will remove specific instances (the ones with 0 pending operations) and prevent them from being restarted.

10. Repeat steps 2-9 until there are no more BLUE tasks.

11. Remove the BLUE app from Marathon.
    
    ```sh
    # remove blue
    dcos marathon app remove /blue-myapp
    ```
