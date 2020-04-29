---
title: Exit Codes 
---

# Exit Codes 

Marathon follows the [Let-it-crash](https://www.reactivedesignpatterns.com/patterns/let-it-crash.html) pattern. Instead
of trying to fix an illegal state it will stop itself to be restarted by its supervisor. The following exit codes should
help you figure out why the Marathon process stopped.

| Exit Code | Reason                                                                                               |
|-----------|------------------------------------------------------------------------------------------------------|
|100        | `ZookeeperConnectionFailure` - Could not connect to Zookeeper                                        |
|101        | `ZookeeperConnectionLost` - Lost connect to Zookeeper                                                |
|102        | `PluginInitializationFailure` - Could not load plugin                                                |
|103        | `LeadershipLoss` - Lost leadership                                                                   |
|104        | `LeadershipEndedFaulty` - Leadership ended with an error                                             |
|105        | `LeadershipEndedGracefully` - Leadership ended without an error                                      |
|106        | `MesosSchedulerError` - The Mesos scheduler driver had an error                                      |
|107        | `UncaughtException` - An internal unknown error could not be handled                                 |
|108        | `FrameworkIdMissing` The Framework ID could not be read                                              |
|109        | `IncompatibleLibMesos` Provided LibMesos version is incompatible                                     |
|110        | `FrameworkHasBeenRemoved` Framework has been removed via Mesos teardown call                         |
|111        | `BindingError` Marathon could not bind to the address provided by `--http_address` and `--http_port` |
|112        | `InvalidCommandLineFlag` Marathon was started with an invalid command line flag. Check the error message for deprecated options. |
|137        | Killed by an external process or uncaught exception                                                  |

## Troubleshooting Exit Codes

### 102 Plugin Initialization Failure

One or more configured plugins could not be initialized. Please check your plugin configuration and the compatibility of the loaded plugin.

### 108 Missing Framework ID

The configured zookeeper path contains references to any app or pod instances but it does not contain a Framework ID for registration with Mesos. Marathon will not register without a Framework ID since this would orphan any existing app or pod instances. Please see the documentation on [Framework ID registration](framework-id.html).

### 110 Framework Has Been Removed

The Framework ID stored in zookeeper is no longer valid for registration with Mesos. Most likely, it has been torn down via the Mesos Master `teardown` endpoint. You should clean up the existing zookeeper node that the Marathon is configured to use. Note that the state may not contain any instances, otherwise Marathon will crash with a `108` exit code. You may keep app or pod definitions, but do this at your own risk.
