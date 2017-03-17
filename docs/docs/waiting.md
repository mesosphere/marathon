---
title: Stuck App or Pod Deployment
---

# Stuck App or Pod Deployments

Apps or pods often fail to deploy because the resource offers from Mesos do not match or cannot match the resources the app or pod requests in the application or pod definition. Below is an overview of the offer matching process.

# How Offer Matching Works

1. You post an application or pod definition to Marathon. The application or pod specifies resource requirements and/or placement constraints as well as the number of instances to launch.

1. Marathon adds the new app or pod to the launch queue.

1. Every 5 seconds (by default), Mesos sends one offer per agent.

1. For each resource offer, Marathon checks if there is an app or pod in the launch queue whose requirements all match the offer. If Marathon finds an app or pod whose requirements and constraints match the offer, Marathon will launch the app or pod.

1. If an offer never arrives that matches an application or pod's requirements and constraints, Marathon will not be able to launch the app or pod.

 **Note:** The required resources (such as CPU, Mem, Disk, and Ports) must all be available on a single host.

# Why Your App or Pod is Stuck

There are several reasons why your app or pod may fail to deploy. Some possibilities include:

- Marathon isn't getting the resource offers it needs to launch the app.
  If you are using DC/OS, use the [CLI](https://dcos.io/docs/1.9/usage/debugging/cli-debugging) debug subcommands or the [debugging page in the DC/OS web interface](https://dcos.io/docs/1.9/usage/debugging/gui-debugging) to troubleshoot unmatched or unaccepted resource offers from Mesos. You can also [consult the service and task logs](https://dcos.io/docs/1.9/administration/logging/).

  Otherwise, consult the Marathon UI and the Mesos UI to see the health and resource use of your app or pod.

- The app or pod's health check is failing.
  If a an app or pod has a health check, deployment does not complete until the health check passes. You can see the health of an app or pod from the Marathon UI.

- `docker pull` is failing.
  If your app runs in a Docker image, the Mesos agent node will first have to pull the Docker image. If this fails, your app could get stuck in a "deploying" state. The Mesos agent logs (`<dcos-url>/mesos/#/agents/`) will contain this information. You will see an error in the log similar to the following.

  ```
  6b50d4f5-05d6-4b99-bb63-115d5acd2aca-0000 failed to start: Failed to run 'docker -H unix:///var/run/docker.sock pull /mybadimage/fakeimage:latest': exited with status 1; stderr='Error parsing reference: "/mybadimage/fakeimage:latest" is not a valid repository/tag
  ```

- Your application, application group, or pod definition is otherwise badly configured.
  You can use the [marathon-validate](https://github.com/dcos-labs/marathon-validate) script to validate an app or group definition locally, before you deploy it to DC/OS.

If you do not find the solution yourself and you create a Github issue, append the output of Mesos `/state` endpoint to the bug report so that we can inspect available cluster resources.
