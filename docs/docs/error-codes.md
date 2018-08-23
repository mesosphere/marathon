---
title: Error Codes 
---

# Error Codes 

Marathon follows the [Let-it-crash](https://www.reactivedesignpatterns.com/patterns/let-it-crash.html) pattern. Instead
of trying to fix an illegal state it will stop itself to be restarted by its supervisor. The following exit codes should
help you figure out why the Marathon process stopped.

| Exit Code | Reason |
|-----------|--------|
|42         | Lost connection to Zookeeper or Mesos |
|137        | Killed by an external process or uncaught exception |

