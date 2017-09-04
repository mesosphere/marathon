---
title: Extend Marathon with Plugins
---


# Extend Marathon with Plugins

<div class="alert alert-danger" role="alert">
  <span class="glyphicon glyphicon-exclamation-sign" aria-hidden="true"></span> Adapted in Marathon Version 0.16 <br/>
  The Marathon plugin functionality is considered beta, so use this feature at you own risk. We might add, change, or delete any functionality described in this document.
</div>

## Overview

<p class="text-center">
  <img src="{{ site.baseurl}}/img/plugin-mechanism.png" width="552" height="417" alt="">
</p>


Starting with Marathon 0.12, we allow you to extend Marathon functionality with plugins.
The plugin mechanism has the following components:

- __Extension-Aware Functionality:__ Marathon makes only specific functionality available for customization through hooks implemented into the main system. These hooks can be refined and extended with external plugins. See the Available Plugin Extensions section of this document for a discussion of these hooks.
- __Extension Interface:__ In order to extend functionality with a plugin, you must implement the extension interface. The extension interface is defined as a Scala trait or Java interface.
- __Plugin Extension:__ A plugin extension is the implementation of a defined extension interface. The plugin needs to be compiled and packaged into one or more separate jar files.
- __Plugin Descriptor:__ The descriptor defines which set of plugins should be enabled in one instance of Marathon.



## Plugin Interface

A separate Marathon plugin interface jar is published in the following Maven repository with every Marathon release:

```
http://downloads.mesosphere.com/maven
```

with following dependency:

SBT:

```
"mesosphere.marathon" %% "plugin-interface" % "x.x.x" % "provided"

```

Maven:

```
<dependency>
    <groupId>mesosphere.marathon</groupId>
    <artifactId>plugin-interface_2.11</artifactId>
    <version>x.x.x</version>
</dependency>
```

Be sure to always use the same version of the plugin interface as the version of Marathon in production.



## Extension Functionality

To create an extension, you must implement an extension interface from the plugin interface and put it in a location available to Marathon so that it can be loaded by the ServiceLoader.

### ServiceLoader

Marathon uses the [ServiceLoader](https://docs.oracle.com/javase/8/docs/api/java/util/ServiceLoader.html)
to make a service provider available to a running Marathon instance. For background information on that topic, please see [Creating Extensible Applications](https://docs.oracle.com/javase/tutorial/ext/basics/spi.html).

### Dependencies on other libraries

The writer of the plugin can define dependencies on other libraries and use those dependencies if following requirements are met:

- Marathon already depends on that library. You can see all Marathon dependencies by checking out Marathon and running `sbt dependencyTree`. Such a dependency should be defined as `provided` in your build tool of choice.
  _Please make sure, you use the exact same version as Marathon!_
- If the library is not provided, it must not conflict with any library bundled with Marathon (for instance, different byte code manipulation libraries are known to interfere).
  Such a dependency should be defined as `compile` dependency in your build tool of choice.

Dependent libraries that are not already provided by Marathon have to be shipped along with the plugin itself.

### Packaging

The plugin has to be compiled and packaged as `jar` file.
Inside that jar file the [ServiceLoader](https://docs.oracle.com/javase/8/docs/api/java/util/ServiceLoader.html)-specific provider-configuration file needs to be included.


### Logging

Marathon uses `slf4j` for logging. If you use these logger classes, all log statements will be included in Marathon's log.

### Configuration

Since the plugin mechanism in Marathon relies on the `ServiceLoader`, the plugin class has to implement a default constructor.
To pass a certain configuration to the plugin, you can use whatever functionality you like.

We built one optional way to pass a configuration to your plugin. The [PluginConfiguration](https://github.com/mesosphere/marathon/blob/master/plugin-interface/src/main/scala/mesosphere/marathon/plugin/plugin/PluginConfiguration.scala) interface
passes the configuration from the plugin descriptor to every created instance of that plugin before any other method is called.


## Plugin Descriptor

You can also extend Marathon with a set of several plugins.
You must provide a plugin descriptor to make sure you are using only valid combinations of plugins.
The descriptor defines which plugin interface is extended by which service provider.
Marathon will load this descriptor with all defined plugins during startup.

- `plugin` (required): the name of the plugin interface.
- `implementation` (required): the name of the implementing class.
- `configuration` (optional): configuration that is available to the plugin via the PluginConfiguration trait.
- `tags` (optional): meta data attached to this plugin as simple strings.
- `info` (optional): meta data attached to this plugin as key/value pair.

Example:

```json
{
  "plugins": {
    "authorizer": {
      "plugin": "mesosphere.marathon.plugin.auth.Authorizer",
      "implementation": "my.company.ExampleAuthorizer",
      "tags": ["some", "tag"],
      "info": {
        "some": "info"
      }
    },
    "authenticator": {
      "plugin": "mesosphere.marathon.plugin.auth.Authenticator",
      "implementation": "my.company.ExampleAuthenticator",
      "configuration": {
        "users": [
          {
            "user": "ernie",
            "password" : "ernie",
            "permissions": [
              { "allowed": "create", "on": "/dev/" }
            ]
          }
        ]
      }
    }
  }
}
```

The configuration attribute is optional. If you provide it, the JSON object will be passed to the plugin as-is.

## Start Marathon with plugins

In order to make your plugins available to Marathon you have to:

- Place your plugin.jar with all dependant jars (that are not already provided by Marathon) into a directory.
- Write a plugin descriptor that lists the plugins to use.

Start Marathon with this options:

- `--plugin_dir path` where path is the directory in which the plugin jars are stored.
- `--plugin_conf conf.json` where conf.json is the full path of the plugin configuration file.


# Available Plugin Extensions

## Security

The security plugins are interceptors on the HTTP REST endpoint.   The plugins will be in a multi-threaded environment invoked for each HTTP request.   The execution time of the HTTP request (which includes the plugin) must be within the configured timeout.  By default this is 10 seconds.

#### mesosphere.marathon.plugin.auth.Authenticator

Marathon exposes an HTTP REST API. To make sure only authenticated identities can access the system, the plugin developer must implement the Authenticator interface.
This interface relies purely on the HTTP Layer. Please see the [Authenticator](https://github.com/mesosphere/marathon/blob/master/plugin-interface/src/main/scala/mesosphere/marathon/plugin/auth/Authenticator.scala) trait for documentation, as well as the [Example Scala Plugin](https://github.com/mesosphere/marathon-example-plugins/tree/master/auth) or the [Example Java Plugin](https://github.com/mesosphere/marathon-example-plugins/tree/master/javaauth).

#### mesosphere.marathon.plugin.auth.Authorizer

This plugin works along with the Authentication plugin. With this interface you can refine which actions an authenticated identity can perform on Marathon's resources.
Please see the [Authorizer](https://github.com/mesosphere/marathon/blob/master/plugin-interface/src/main/scala/mesosphere/marathon/plugin/auth/Authorizer.scala) trait for documentation as well as [Example Scala Plugin](https://github.com/mesosphere/marathon-example-plugins/tree/master/auth) or the [Example Java Plugin](https://github.com/mesosphere/marathon-example-plugins/tree/master/javaauth).

## RunSpec

#### mesosphere.marathon.plugin.task.RunSpecTaskProcessor

This plugin allows for the modification of an app TaskInfo or the pod TaskGroupInfo.  This could be used to modify the runSpec by adding labels or to enforce the use of a custom executor.
Please see the [RunSpecTaskProcessor]((https://github.com/mesosphere/marathon/blob/master/plugin-interface/src/main/scala/mesosphere/marathon/plugin/task/RunSpecTaskProcessor.scala)) trait for documentation  as well as [Example Scala Plugin](https://github.com/mesosphere/marathon-example-plugins/tree/master/env).

When working with the `RunSpecTaskProcessor` is best to be familiar with [ProtocolBuffers](https://developers.google.com/protocol-buffers/docs/overview) and the Apache Mesos [org.apache.mesos.Protos.TaskInfo](http://mesos.apache.org/api/latest/java/org/apache/mesos/Protos.TaskInfo.html)


#### mesosphere.marathon.plugin.validation.RunSpecValidator

This plugin allow for the validation of an app or pod at the time the runspec is added or updated.  This allows for the enforcement of specific rules on the runspec.  
Please see the [RunSpecValidator](https://github.com/mesosphere/marathon/blob/master/plugin-interface/src/main/scala/mesosphere/marathon/plugin/validation/RunSpecValidator.scala) trait for documentation as well as [Example Scala Plugin](https://github.com/mesosphere/marathon-example-plugins/tree/master/label).

Marathon and the `RunSpecValidator` use the [Accord](https://github.com/wix/accord) validation library which is useful to understand when creating validator rules.

## Scheduler

#### mesosphere.marathon.plugin.scheduler.SchedulerPlugin

This plugin allows to reject offers. Possible use-cases are:
* Maintenance. Mark agent as going to maintenance and reject new offers from it.
* Analytics. If task fails, for example, 5 times for 5 minutes, we can assume that it will fail again and reject new offers for it.
* Binding to agents. For example, agents can be marked as included into primary or secondary group. Task can be marked with group name.
 Plugin can schedule task deployment to primary agents. If all primary agents are busy, task can be scheduled to secondary agents

## Notes on Plugins

* The plugins allow for the extension of behavior in Marathon.  They do NOT allow for replacement or removal of existing functionality.
* It is possible to have multiple configured plugins for the same plugin site.  There are no guarantees on the order of plugin invocation.
* Exceptions within plugins are NOT currently isolated and could affect other aspects of Marathon.
