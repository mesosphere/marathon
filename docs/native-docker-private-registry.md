---
title: Using a Private Docker Registry
---

# Using a Private Docker Registry

This document describes how to initiate a [Docker](https://docker.com/) pull from
an authenticated private registry.

## Registry  1.0 - Docker pre 1.6 
To supply credentials to pull from a private registry, add a `.dockercfg` to
the `uris` field of your app. The `$HOME` environment variable will then be set
to the same value as `$MESOS_SANDBOX` so Docker can automatically pick up the
config file.


## Registry  2.0 - Docker 1.6 and up

To supply credentials to pull from a private registry, add a `docker.tar.gz` file to
the `uris` field of your app. The `docker.tar.gz` file should include the `.docker` folder and the contained `.docker/config.json` 


### Step 1: Tar/Gzip credentials

1. Login to the private registry manually. Login creates a `.docker` folder and a `.docker/config.json` in the users home directoy

    ```bash
    $ docker login some.docker.host.com
      Username: foo 
      Password: 
      Email: foo@bar.com
    ```

1. Tar this folder and it's contents

    ```bash
    $ cd ~
    $ tar czf docker.tar.gz .docker
    ```
1. Check you have both files in the tar

    ```bash
    $ tar -tvf ~/docker.tar.gz

      drwx------ root/root         0 2015-07-28 02:54 .docker/
      -rw------- root/root       114 2015-07-28 01:31 .docker/config.json
    ``` 

1. Put the gziped file in location which can be retrieved via mesos/marathon (optional).

    ```bash
    $ cp docker.tar.gz /etc/
    ```

      <div class="alert alert-info">
        <strong>Note:</strong> 
       The URI must be accessible by all nodes that may start your application.
       Approaches may include distributing the file to the local filesystem of all nodes, for example via RSYNC/SCP, or
       storing it on a shared network drive, for example [Amazon S3](http://aws.amazon.com/s3/).
       It is worth considering the security implications of your chosen approach.
      </div>


### Step 2: Mesos/Marathon config

1. Add the path to the gzipped login credentials to your Marathon app definition

    ```bash
    "uris": [
       "file:///etc/docker.tar.gz"
    ]
    ```

1. For example:

    ```json
    {  
      "id": "/some/name/or/id",
      "cpus": 1,
      "mem": 1024,
      "instances": 1,
      "container": {
        "type": "DOCKER",
        "docker": {
          "image": "some.docker.host.com/namespace/repo",
          "network": "HOST"
        }
      },
      "uris":  [
          "file:///etc/docker.tar.gz"
      ]
    }
    ```

1. Docker image will now pull using the provided security credentials given.