---
title: Application Basics
---

# Application Basics

Applications are an integral concept in Marathon. <!-- are they a concept? if so, what more can we say about this concept? --> Each application typically represents a long-running service, of which there would be many instances running on multiple hosts.

## Hello Marathon: An Inline Shell Script

Let's start with a simple example: an app that prints `Hello Marathon` to stdout and then sleeps for 5 sec, in an endless loop.

Use the following JSON application definition to describe the application <!-- how to do launch this? try to find the dcos command -->: 

```json
{
    "id": "basic-0", 
    "cmd": "while [ true ] ; do echo 'Hello Marathon' ; sleep 5 ; done",
    "cpus": 0.1,
    "mem": 10.0,
    "instances": 1
}
```

Note that `cmd` in the above example is the command that is executed. Its value is wrapped by the underlying Mesos executor via `/bin/sh -c ${cmd}`. <!-- this last sentence: why is it relevant to the user at this point? -->

<p class="text-center">
  <img src="{{ site.baseurl }}/img/marathon-basic-0.png" width="800" height="612" alt="Marathon deployment example: simple bash command">
</p>

What happens here <!-- where is here? I want to rephrase this to "When you launch an app, Marathon... --> is that Marathon hands over execution to Mesos. Mesos executes each task in its own sandbox environment.
The sandbox is a special directory on each slave that acts as the execution environment (from a storage perspective <!-- what does this mean? -->) and contains relevant log files as well as `stderr` and `stdout` for the command being executed. See also the role of the sandbox in [debugging distributed apps](https://docs.mesosphere.com/tutorials/debugging-a-mesosphere-cluster/).


## Using Resources in Applications

To run any non-trivial application, you typically depend on a collection of resources: files and/or archives of files. To deal with this <!-- I'd like to be more precise here: what is there to "deal with"? how can we describe the challenge here? -->, Marathon has the concept of `uris`. <!-- what is the connection between these two sentencences? --> Marathon leverages the Mesos fetcher to do the legwork in terms of downloading (and potentially) extracting resources.

But before we dive into this topic, let's have a look at an example:

```json
{
    "id": "basic-1", 
    "cmd": "./cool-script.sh",
    "cpus": 0.1,
    "mem": 10.0,
    "instances": 1,
    "uris": [
        "https://example.com/app/cool-script.sh"
    ]
}
```

The example above downloads the resource `https://example.com/app/cool-script.sh` (via Mesos) and makes it available in the application task's sandbox before executing the `cmd`. You can check that these have been downloaded by selecting the sandbox of a Mesos worker node in the Mesos UI. You should find `cool-script.sh`.

Note that as of Mesos v0.22 and above the fetcher code does not make downloaded files executable by default, so `cmd` should be: `chmod u+x cool-script.sh && ./cool-script.sh`. <!-- should we just have this be cmd, then comment on it? wouldn't most people be using the latest Mesos? -->

Marathon [handles application resources in archives](https://github.com/mesosphere/marathon/blob/master/src/main/scala/mesosphere/mesos/TaskBuilder.scala). Before executing the `cmd`, Marathon will attempt (via Mesos) to unpack/extract resources with the following file extensions:

* `.tgz`
* `.tar.gz`
* `.tbz2`
* `.tar.bz2`
* `.txz`
* `.tar.xz`
* `.zip`

Let's assume you have an application executable in a zip file at `https://example.com/app.zip`. This zip file contains the script `cool-script.sh`, which you want to execute. The following code snippet will extract and execute `cool-script.sh`:

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

In contrast to the example `basic-1`, `cmd` now specifies the `app` directory: `app/cool-script.sh`. After the zip file is downloaded, it is extracted into a directory with the same filename. <!-- <-- need to clarify this --> So `app.zip` is extracted into a directory called `app`.

You can specify more than one resource. For example, you can provide a git repository and resources from a CDN:

```json
{
    ...
    "uris": [
        "https://git.example.com/repo-app.zip", "https://cdn.example.net/my-file.jpg", "https://cdn.example.net/my-other-file.css"
    ]
    ...
}
```

A typical pattern in the development and deployment cycle is to have your automated build system place the app binary in a location is accessible via URI. Marathon supports the following [URI schemes](http://tools.ietf.org/html/rfc3986#section-3.1):

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

It is straightforward to run applications that use Docker images in Marathon. See [Running Docker Containers on Marathon]({{ site.baseurl }}/docs/native-docker.html) for further details and advanced options.

In the following example application definition, we will focus on a simple Docker app: a Python-based web server using the image [python:3](https://registry.hub.docker.com/_/python/). Inside the container, the web server runs on port `8080` (the value of `containerPort`). Outside of the container, Marathon assigns a random port (`hostPort` is set to `0`): <!-- does this mean that if you set hostPort to 0 you'll get assigned a random port? -->

```json
{
  "id": "basic-3",
  "cmd": "python3 -m http.server 8080",
  "cpus": 0.5,
  "mem": 32.0,
  "container": {
    "type": "DOCKER",
    "docker": {
      "image": "python:3",
      "network": "BRIDGE",
      "portMappings": [
        { "containerPort": 8080, "hostPort": 0 }
      ]
    }
  }
}
```

In this example, we use the [HTTP API]({{ site.baseurl }}/docs/rest-api.html) to deploy the app `basic-3`:

```sh
curl -X POST http://10.141.141.10:8080/v2/apps -d @basic-3.json -H "Content-type: application/json"
```

This assumes that you've pasted the example JSON into a file called `basic-3.json` and you're using [playa-mesos](https://github.com/mesosphere/playa-mesos), a Mesos sandbox environment based on Vagrant, to test the deployment. When you submit the above application definition to Marathon you should see your application in the tasks and configuration tabs of the [Marathon UI]({{ site.baseurl }}/docs/marathon-ui.html):

<p class="text-center">
  <img src="{{ site.baseurl }}/img/marathon-basic-3-tasks.png" width="800" height="612" alt="Marathon deployment example: Docker image, tasks">
</p>

<p class="text-center">
  <img src="{{ site.baseurl }}/img/marathon-basic-3-config.png" width="800" height="612" alt="Marathon deployment example: Docker image, configuration">
</p>

Marathon has launched a Python-based web server in a Docker container, which is now serving the contents of the container's root directory at `http://10.141.141.10:31000`.
