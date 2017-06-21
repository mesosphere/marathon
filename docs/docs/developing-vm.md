---
title: Developing Marathon in a Virtual Machine
---

# Developing Marathon in a Virtual Machine

This will enable you to run a local version of Marathon, for development purposes, without having to compile and configure a local Mesos environment.

1. Ensure your local Marathon has been compiled and assembled. In the `marathon` directory:
    
    ```
    sbt assembly
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
