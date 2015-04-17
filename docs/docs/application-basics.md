---
title: Application Basics
---

# Application Basics

Applications are an integral concept in Marathon. Each application typically represents a long running service, of which there would be many instances running on multiple hosts.

Let us start with a simple example: an app that prints `Hello Marathon` to stdout and then sleeps for 5 sec, in an endless loop.
You would use the following application definition (in JSON format) to describe the application: 

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
  <img src="{{ site.baseurl}}/img/marathon-basic-0.png" width="800" height="573" alt="Marathon deployment example: simple bash command">
</p>

What happens here is that Marathon hands over execution to Mesos. Mesos executes each task in its own sandbox environment.
The sandbox is a special directory on each slave that acts as the execution environment (from a storage perspective) and also contains relevant log files as well as `stderr` and `stdout` for the command being executed. See also the role of the sandbox in [debugging distributed apps](https://docs.mesosphere.com/tutorials/debugging-a-mesosphere-cluster/).

For any non-trivial application you typically depend on a collection of resources, that is, files and/or archives of files. To deal with this, Marathon has the concept of `uris`. It leverages the Mesos fetcher to do the legwork in terms of downloading (and potentially) extracting resources.

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

Above means: before executing the `cmd`, download the resource `https://example.com/app/cool-script.sh` (via Mesos) and make it available in the apps sandbox. You can check that through visiting the Mesos UI and click into a Mesos worker node's sandbox where you'll find `cool-script.sh`.
Note that as of Mesos v0.22 and above the executor code has changed and `cmd` should be: `chmod u+x cool-script.sh && ./cool-script.sh`.


As already mentioned above, Marathon also [knows how to handle](https://github.com/mesosphere/marathon/blob/master/src/main/scala/mesosphere/mesos/TaskBuilder.scala) application resources that reside in archives. Currently, Marathon will (through Mesos and before executing the `cmd`) first attempt to unpack/extract resources with the following file extensions:

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
