---
title: Developing Marathon
---

# Coding environment

## IntelliJ

The following project configuration should be enabled when developing Marathon:

* Build, Execution, Deployment > Build Tools > sbt
    * Enable the option "Enable use sbt shell: for builds"

### Common issues

#### Local maven repository is not seen

If you are publishing USI dependencies locally using `./gradlew publishMavenJavaPublicationToMavenLocal` (please catch your breath if reading out loud), and get the error that the dependency is not found, you need to configure sbt to globally look for your local maven repo. Usually, this is done by adding the following file:

`~/.sbt/1.0/local-maven.sbt`

```scala
resolvers := {
  val localMaven = "Local Maven Repository" at "file://"+Path.userHome.absolutePath+"/.m2/repository"
  localMaven +: resolvers.value
}
```


#### Compiler issue "Protos is already defined as class Protos"

If you see the error:

```
./marathon/target/scala-2.12/src_managed/main/compiled_protobuf/mesosphere/marathon/Protos.java
Error:(6, 20) Protos is already defined as class Protos
public final class Protos {
```

... this happens when you're using IntelliJ to build the project, rather than sbt. Ensure you have the project configured as specified above.

# Marathon UI

To develop on the web UI look into the instructions of the [Marathon UI](https://github.com/mesosphere/marathon-ui) repository.

# Testing

The tests and integration tests a run with:

    sbt test integration/test

You have to set the Mesos test IP and disable Docker tests on Mac:

    MESOSTEST_IP_ADDRESS="127.0.0.1" \
    RUN_DOCKER_INTEGRATION_TESTS=false \
    RUN_MESOS_INTEGRATION_TESTS=false \
    sbt test integration/test

The Docker integration tests are not supported on Mac. The tests start and stop
local Mesos clusters and Marathon instances. Sometimes processes leak after
failed test runs. You can check them with `ps aux | grep "python|java|mesos"`
and kill all `app_mock.py` processes and Mesos and Marathon instances unless
they do not belong to a production environment of course.

Also see the [CI instructions](ci/README.md) on running specfic build pipeline
targets.



# Running Marathon

## Running in a VM

See [the documentation](https://mesosphere.github.io/marathon/docs/developing-vm.html) on how to run Marathon locally inside a virtual machine.

## Running in Development Mode on Docker

* Note: Currently the Docker container fails due to strange behavior from the latest Mesos version.  There will be an error about `work_dir` that is still unresolved, much like this:

        Failed to start a local cluster while loading agent flags from the environment: Flag 'work_dir' is required, but it was not provided

Build it:

    cd tools/packager; make tag-docker

The image will be tagged locally according to the version script output `./version`.

A running zookeeper instance is required, if there isn't one already available, there is a docker image available for this:

    docker run --name some-zookeeper --restart always -d zookeeper

Run it with zookeeper container:

    docker run --link some-zookeeper:zookeeper marathon-head --master local --zk zk://zookeeper:2181/marathon

Or run it without zookeeper container:

    docker run marathon:{version} --master local --zk zk://localhost:2181/marathon

If you want to inspect the contents of the Docker container:

    docker run -it --entrypoint=/bin/bash marathon:{version} -s


## Developing Marathon in a Virtual Machine

This will enable you to run a local version of Marathon, for development purposes, without having to compile and configure a local Mesos environment.

1. Ensure your local Marathon has been compiled and assembled. In the `marathon` directory:

    ```
    sbt package
    ```

2. Does your local Marathon have custom Javascript changes that you expect to see?
   If so you'll need to compile the assets. Here is a guide to working on assets:
   [Compiling Assets](https://github.com/mesosphere/marathon-ui#compiling-assets).

3.  Clone the [playa-mesos repository](https://github.com/mesosphere/playa-mesos). Note that playa-mesos ships with a version of Mesos, Marathon, and ZooKeeper pre-configured.

4.  Sync local folders into Vagrant image

    Open `playa-mesos/Vagrantfile` and edit the `override.vm.synced_folder` setting:

    ```
    config.vm.synced_folder '</path/to/marathon/parent>', '/vagrant'
    ```
    Here `</path/to/marathon/parent>` is the absolute path to the folder containing Marathon.

5. SSH into your Vagrant image

    ``` console
    $ vagrant up # if not already running otherwise `vagrant reload`
    $ vagrant ssh
    ```

6.  Check that your folders are synced correctly

    ``` console
    $ cd /vagrant/
    $ ls
    marathon playa-mesos ...
    ```

7. Stop the Marathon that is pre-configured in the Vagrant image

    ``` console
    $ sudo stop marathon
    ```

8. Add `start-marathon` alias to start your own version of Marathon

    ``` console
    $ nano ~/.bash_aliases
    ```

    add the following in the top of that file and save it:

    ``` bash
    # setup marathon easy run
    alias 'start-marathon'='/vagrant/marathon/bin/start --master zk://localhost:2181/mesos --zk zk://localhost:2181/marathon --assets_path src/main/resources/assets'
    ```

9.  Refresh the terminal and run the `start-marathon` command in the marathon folder

    ``` console
    $ . ~/.bashrc
    $ start-marathon
    ```

10. Load the [Marathon UI]({{ site.baseurl }}/docs/marathon-ui.html) in your browser: http://10.141.141.10:8080

When you're done, use `vagrant halt` to shut down the Vagrant instance and spare your battery life.
