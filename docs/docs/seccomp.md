---
title: seccomp
---

Secure computing mode (seccomp) is a Linux kernel feature used to restrict the actions available within a running container. The seccomp() system call operates on the seccomp state of the calling process. You can use this feature to restrict your application’s access to the underlying system.

Seccomp support was introduced in Mesos 1.8 which [introduces the ability to configure seccomp](http://mesos.apache.org/documentation/latest/isolators/linux-seccomp/) through the UCR containerizer to provide a higher degree of isolation and security to services deployed on Mesos. In order to use seccomp within Marathon, it is necessary to configure the Mesos agents in a Mesos cluster to enable seccomp with the seccomp isolator.

# Marathon Apps/Pods under a Default Profile
Once Mesos agents are configured with the seccomp isolator and a default seccomp profile, all Marathon launched tasks will launch under that seccomp profile if their corresponding services do not have a seccomp configuration.

# Opting Out of Seccomp
It is possible to have a service opt-out of running under seccomp. For a Marathon-defined service, this is accomplished by defining `unconfined=true` in the `seccomp` object under the `LinuxInfo` configuration setting for a container. For example:

```
{
  "id": "/mesos-seccomp-app",
  "cmd": "sleep 1000",
  "cpus": 0.5,
  "mem": 32,
  "container": {
    "type": "MESOS",
    "linuxInfo": {
      "seccomp": {
        "unconfined": true
      }
    }
  }
}
```

By configuring a service definition with an `unconfined` seccomp setting, the container will NOT run under seccomp. This will allow this container to execute any syscall that might have been restricted by the default seccomp profile.


# Running under a New Seccomp Profile
It is also possible to have a service definition run under a different seccomp profile other than the default. This is accomplished by specifying the profile name in the seccomp definition for the service definition. For example:

```
{
  "id": "/mesos-seccomp-app",
  "cmd": "sleep 1000",
  "cpus": 0.5,
  "mem": 32,
  "container": {
    "type": "MESOS",
    "linuxInfo": {
      "seccomp": {
        "profileName": "relaxed.json"
      }
    }
  }
}
```

This service definition expects that any agent which this service could launch on has a seccomp profile named `relaxed.json` in the `seccomp_config_dir` folder (defined on the Mesos agent). When this container starts on that agent, it runs under seccomp control and the restrictions defined in the `relaxed.json` profile configuration. In this example, the service will not be restricted by the configuration defined in the `default.json` seccomp profile. Instead, the service runs under the restrictions defined in the custom `relaxed.json` profile.

# Consequence of running under seccomp
Seccomp is a security mechanism that reduces the surface area of attack on a system by restricting which syscalls are allowed from inside the container. While a container is running under seccomp restrictions, if a restricted call is attempted, the result is the task process will fail. Marathon will see that the task failed and will reschedule the task based on the task failure. Assuming the task will invoke the restricted syscall again, this will result in Marathon going in a [backoff delay]({{ site.baseurl }}/docs/backoff) for this service.
