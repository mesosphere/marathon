---
title: Application Basics
---

# Application Basics

Applications are an integral concept in Marathon. Each application typically represents a long-running service, of which there would be many instances running on multiple hosts. An application instance is called a *task*. The *application definition* describes everything needed to start and maintain the tasks. 


## Hello Marathon: An Inline Shell Script

Let's start with a simple example: an app that prints `Hello Marathon` to stdout and then sleeps for 5 sec, in an endless loop.
You would use the following JSON application definition to describe the application: 

```json
{
    "id": "basic-0", 
    "cmd": "while [ true ] ; do echo 'Hello Marathon' ; sleep 5 ; done",
    "cpus": 0.1,
    "mem": 10.0,
    "instances": 1
}
```

Note that `cmd` in the above example is the command that gets executed. Its value is wrapped by the underlying Mesos executor via `/bin/sh -c ${cmd}`.

<p class="text-center">
  <img src="{{ site.baseurl }}/img/marathon-basic-0.png" width="800" height="612" alt="Marathon deployment example: simple bash command">
</p>

When you define and launch an application, Marathon hands over execution to Mesos. Mesos creates a sandbox directory for each task. The sanbox directory is a directory on each agent node that acts as an execution environment and contains relevant log files. The `stderr` and `stdout` streams are also written to the sanbox directory.

## Using Resources in Applications

To run any non-trivial application, you typically depend on a collection of resources: files and/or archives of files. To manage resource allocation, Marathon has the concept of URIs. URIs use the Mesos fetcher to do the legwork in terms of downloading (and potentially) extracting resources.

Before we dive into this topic, let's have a look at an example:

```json
{
    "id": "basic-1", 
    "cmd": "`chmod u+x cool-script.sh && ./cool-script.sh`",
    "cpus": 0.1,
    "mem": 10.0,
    "instances": 1,
    "uris": [
        "https://example.com/app/cool-script.sh"
    ]
}
```

What the example above does: before executing the `cmd`, download the resource `https://example.com/app/cool-script.sh` (via Mesos) and make it available in the application task's sandbox. You can check that these have been downloaded by visiting the Mesos UI and clicking into a Mesos worker node's sandbox where you should now find `cool-script.sh`.

**Note:** As of Mesos v0.22 and above, the fetcher code does not make downloaded files executable by default. In the example above, `cmd` first makes the file executable.

As already mentioned above, Marathon also [knows how to handle](https://github.com/mesosphere/marathon/blob/master/src/main/scala/mesosphere/mesos/TaskBuilder.scala) application resources that reside in archives. Currently, Marathon will (via Mesos and before executing the `cmd`) first attempt to unpack/extract resources with the following file extensions:

* `.tgz`
* `.tar.gz`
* `.tbz2`
* `.tar.bz2`
* `.txz`
* `.tar.xz`
* `.zip`

And how this looks in practice shows you the following example: let's assume you have an application executable in a zip file at `https://example.com/app.zip`. This zip file contains the script `cool-script.sh` and that's what you want to execute. Here's how:

```json
{
    "id": "basic-2", 
    "cmd": "app/cool-script.sh",
    "cpus": 0.1,
    "mem": 10.0,
    "instances": 1,
    "uris": [
        "https://example.com/app.zip"
    ]
}
```

Note that in contrast to the example `basic-1` we now have a `cmd` that looks as follows: `app/cool-script.sh`. This stems from the fact that when the zip file gets downloaded and extracted, a directory `app` according to the file name `app.zip` is created where the content of the zip file is extracted into.

Note also that you can specify many resources, not only one. So, for example, you could provide a git repository and some resources from a CDN as follows:

```json
{
    ...
    "uris": [
        "https://git.example.com/repo-app.zip", "https://cdn.example.net/my-file.jpg", "https://cdn.example.net/my-other-file.css"
    ]
    ...
}
```

A typical pattern in the development and deployment cycle is to have your automated build system place the app binary in a location that's downloadable via an URI. Marathon can download resources from a number of sources, supporting the following [URI schemes](http://tools.ietf.org/html/rfc3986#section-3.1):

* `file:`
* `http:`
* `https:`
* `ftp:`
* `ftps:`
* `hdfs:`
* `s3:`
* `s3a:`
* `s3n:`

## A Simple Docker-based Application

With Marathon it is straightforward to run applications that use Docker images. See also [Running Docker Containers on Marathon]({{ site.baseurl }}/docs/native-docker.html) for further details and advanced options.

In the following example application definition, we will focus on a simple Docker app: a Python-based web server using the image [python:3](https://registry.hub.docker.com/_/python/). Inside the container, the web server runs on port `8080` (the value of `containerPort`). `hostPort` is set to `0` so that Marathon assigns a random port on the Mesos agent, which is mapped to port 8080 inside the container.

```json
{
  "id": "basic-3",
  "cmd": "python3 -m http.server 8080",
  "cpus": 0.5,
  "mem": 32.0,
  "networks": [ { "mode": "container/bridge" } ],
  "container": {
    "type": "DOCKER",
    "docker": {
      "image": "python:3"
    },
    "portMappings": [
      { "containerPort": 8080, "hostPort": 0 }
    ]
  }
}
```

In this example, we are going to use the [HTTP API]({{ site.baseurl }}/docs/rest-api.html) to deploy the app `basic-3`:

```sh
curl -X POST http://10.141.141.10:8080/v2/apps -d @basic-3.json -H "Content-type: application/json"
```

This assumes that you've pasted the example JSON into a file called `basic-3.json` and you're using [playa-mesos](https://github.com/mesosphere/playa-mesos), a Mesos sandbox environment based on Vagrant, for testing out the deployment. When you submit the above application definition to Marathon you should see something like the following in the [Marathon UI]({{ site.baseurl }}/docs/marathon-ui.html) (for the tasks and configuration tabs, respectively):

<p class="text-center">
  <img src="{{ site.baseurl }}/img/marathon-basic-3-tasks.png" width="800" height="612" alt="Marathon deployment example: Docker image, tasks">
</p>

<p class="text-center">
  <img src="{{ site.baseurl }}/img/marathon-basic-3-config.png" width="800" height="612" alt="Marathon deployment example: Docker image, configuration">
</p>

The result of this exercise is that Marathon has launched a Python-based web server in a Docker container, which is now serving the contents of the container's root directory at `http://10.141.141.10:31000`.
