---
title: Exit Codes 
---

# Exit Codes 

Marathon follows the [Let-it-crash](https://www.reactivedesignpatterns.com/patterns/let-it-crash.html) pattern. Instead
of trying to fix an illegal state it will stop itself to be restarted by its supervisor. The following exit codes should
help you figure out why the Marathon process stopped.

| Exit Code | Reason                                                               |
|-----------|----------------------------------------------------------------------|
|100        | `ZookeeperConnectionFailure` - Could not connect to Zookeeper        |
|101        | `ZookeeperConnectionLost` - Lost connect to Zookeeper                |
|102        | `PluginInitializationFailure` - Could not load plugin                |
|103        | `LeadershipLoss` - Lost leadership                                   |
|104        | `LeadershipEndedFaulty` - Leadership ended with an error             |
|105        | `LeadershipEndedGracefully` - Leadership ended without an error      |
|106        | `MesosSchedulerError` - The Mesos scheduler driver had an error      |
|107        | `UncaughtException` - An internal unknown error could not be handled |
|108        | The Framework ID could not be read.                                  |
|109        | Provided LibMesos version is incompatible.                           |
|137        | Killed by an external process or uncaught exception                  |
