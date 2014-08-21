---
title: Developing Marathon in Vagrant
---

# Developing Marathon in Vagrant

This will enable you to run a local version of Marathon, for development purposes, without having to compile and configure a local Mesos environment.

1.  Clone the [playa-mesos repository](https://github.com/mesosphere/playa-mesos). Note that playa-mesos ships with a version of Mesos, Marathon, and ZooKeeper pre-configured.

2.  Sync local folders into Vagrant image

    Open `playa-mesos/Vagrantfile` and edit the `override.vm.synced_folder` setting:

    ```
    override.vm.synced_folder '</path/to/marathon/parent>', '/vagrant'
    ```
    Here `</path/to/marathon/parent>` is the absolute path to the folder containing Marathon.

3. SSH into your Vagrant image

    ``` console
    $ vagrant up # if not already running otherwise `vagrant reload`
    $ vagrant ssh
    ```

4.  Check that your folders are synced correctly

    ``` console
    $ cd /vagrant/
    $ ls
    marathon playa-mesos ...
    ```

5. Stop the Marathon that is pre-configured in the Vagrant image

    ``` console
    $ sudo stop marathon
    ```

6. Add `marathon-start` alias to start your own version of Marathon

    ``` console
    $ nano ~/.bash_aliases
    ```

    add the following in the top of that file and save it:

    ``` bash
    # setup marathon easy run
    alias 'start-marathon'='./bin/start --master zk://localhost:2181/mesos --zk_hosts localhost:2181 --assets_path src/main/resources/assets'
    ```

7.  Refresh the terminal and run the `start-marathon` command in the marathon folder

    ``` console
    $ . ~/.bashrc
    $ cd /vagrant/marathon/
    $ start-marathon
    ```

When you're done use `vagrant halt` to shut down the Vagrant instance and spare your battery life.
