---
title: Stateful Applications Using External Persistent Volumes
---

# Stateful Applications Using External Persistent Volumes

<div class="alert alert-danger" role="alert">
  <span class="glyphicon glyphicon-exclamation-sign" aria-hidden="true"></span> Adapted in Marathon Version 1.0 <br/>
  External persistent storage functionality is considered beta, so use this feature at your own risk. We might add, change, or delete any functionality described in this document.
  This functionality is **disabled by default** but may be turned on by including `external_volumes` in the value of the `--enable_features` command-line flag.
</div>

Marathon applications normally lose their state when they terminate and are relaunched. In some contexts, for instance, if your application uses MySQL, you’ll want your application to preserve its state. You can use an external storage service, such as Amazon's Elastic Block Store (EBS), to create a persistent volume that follows your application instance.

Using an external storage service allows your apps to be more fault-tolerant. If a host fails, Marathon reschedules your app on another host, along with its associated data, without user intervention.

# Specifying an External Volume

If you are running Marathon on DCOS, add the following to the `genconf/config.yml` file you use during DCOS installation. [Learn more](https://docs.mesosphere.com/concepts/installing/installing-enterprise-edition/configuration-parameters/). If you'd like to test this functionality without DCOS, [start here](https://blog.emccode.com/2016/02/11/give-mesos-and-external-volumes-a-spin-with-playa-mesos/).

- `rexray_config_method: file`

- `rexray_config_filename: /path/to/rexray.yaml`

## Scaling your App

Apps that use external volumes should only be scaled to a single instance because a volume can only attach to a single task at a time. This will likely change in a future release.

If you scale your app down to 0 instances, the volume is detached from the agent where it was mounted, but it is not deleted. If you scale your app up again, the data that had been associated with it will still be available.

## Create an Application with External Volumes

### Using a Mesos Container

You specify an external volume in the app definition of your Marathon app. [Learn more about Marathon application definitions](application-basics.html).

```json
{
  "id": "hello",
  "instances": 1,
  "cpus": 0.1,
  "mem": 32,
  "cmd": "/usr/bin/tail -f /dev/null",
  "container": {
    "type": "MESOS",
    "volumes": [
      {
        "containerPath": "/tmp/test-rexray-volume",
        "external": {
          "size": 100,
          "name": "my-test-vol",
          "provider": "dvdi",
          "options": { "dvdi/driver": "rexray" }
          },
        "mode": "RW"
      }
    ]
  },
  "upgradeStrategy": {
    "minimumHealthCapacity": 0,
    "maximumOverCapacity": 0
  }
}
```

In the app definition above:

- `containerPath` specifies where the volume is mounted inside the container. See [the REX-Ray documentation on data directories](https://rexray.readthedocs.org/en/v0.3.2/user-guide/config/#data-directories) for more information.

- `name` is the name by which your volume driver looks up your volume. When your task is staged on an agent, the volume driver queries the storage service for a volume with this name. If one does not exist, it's created. Otherwise, the existing volume is re-used. **Note:** Implicit volume creation only works when using volumes with a Mesos container and requires that you set `volumes[x].external.size`.

- The `external.options["dvdi/driver"]` option specifies which Docker volume driver to use for storage. If you are running Marathon on DCOS, this value should likely be `rexray`. [Learn more about REX-Ray](https://rexray.readthedocs.org/en/v0.3.2/user-guide/schedulers/).

- You can specify additional options with `container.volumes[x].external.options[optionName]`. The dvdi provider for Mesos containers uses `dvdcli`, which offers the options [documented here](https://github.com/emccode/dvdcli#extra-options). The availability of any given option depends on your volume driver, however.

- Create multiple volumes by adding additional items in the `container.volumes` array.

- Volume parameters cannot be changed after you create the application.

- **Note:** Marathon will not launch apps with external volumes if  `upgradeStrategy.minimusHealthCapacity` is less than 0.5, or if `upgradeStrategy.maximumOverCapacity` does not equal 0.

### Using a Docker Container

Below is a sample app definition that uses a Docker container and specifies an external volume:

```json
{
  "id": "/test-docker",
  "instances": 1,
  "cpus": 0.1,
  "mem": 32,
  "cmd": "/usr/bin/tail -f /dev/null",
  "container": {
    "type": "DOCKER",
    "docker": {
      "image": "alpine:3.1",
      "network": "HOST",
      "forcePullImage": true
    },
    "volumes": [
      {
        "containerPath": "/data/test-rexray-volume",
        "external": {
          "name": "my-test-vol",
          "provider": "dvdi",
          "options": { "dvdi/driver": "rexray" }
        },
        "mode": "RW"
      }
    ]
  },
  "upgradeStrategy": {
    "minimumHealthCapacity": 0,
    "maximumOverCapacity": 0
  }
}
```

For more information, refer to the [REX-Ray documentation](https://rexray.readthedocs.org/en/v0.3.2/user-guide/schedulers/#docker-containerizer-with-marathon).

## Potential Pitfalls

- You can only assign one task per volume. Your storage provider may have other particular limitations.

- The volumes you create are not automatically cleaned up. If you delete your cluster, go to your storage provider and delete the volumes you no longer need. If you're using EBS, find them by searching by the `container.volumes.external.name` that you set in your Marathon app definition. This name corresponds to an EBS volume `Name` tag.

- Volumes are namespaced by their storage provider. If you're using EBS, volumes created on the same AWS account share a namespace. Choose unique volume names to avoid conflicts.

- Docker apps do not support external volumes on DCOS installations running Docker older than 1.8. Currently, this means that DCOS Community Edition users cannot create Docker apps with external volumes.

- If you are using Amazon's EBS, it is possible that clusters can be created in different availability zones (AZs). This means that if you create a cluster with an external volume in one AZ and destroy it, a new cluster may not have access to that external volume because it could be in a different AZ.

- Launch time may increase for applications that create volumes implicitly. The amount of the increase depends on several factors
(including, but not limited to) the size and type of the volume. Your storage provider's method of handling volumes can also influence launch time for implicitly created volumes.

- If tasks using external volumes are not working, or not working the way you expect, consult the agent or system logs to troubleshoot. If you are using REX-Ray on DCOS, you can also consult the systemd journal.

For more information, see the [Apache Mesos documentation on persistent volumes](http://mesos.apache.org/documentation/latest/persistent-volume/).
