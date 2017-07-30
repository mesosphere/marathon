---
title: An App Does Not Leave "Waiting"
---

# An app Does Not Leave "Waiting"
 
This means that Marathon does not receive "Resource Offers" from Mesos that allow it to start tasks of
this application. The simplest failure is that there are not sufficient resources available in the cluster or another
framework hoards all these resources. You can check the Mesos UI for available resources. Note that the required resources
(such as CPU, Mem, Disk) have to be all available on a single host.

If you do not find the solution yourself and you create a github issue, please append the output of Mesos `/state` endpoint to the bug report so that we can inspect available cluster resources.
