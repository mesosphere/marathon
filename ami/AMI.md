Marathon Jenkins AMI
====================

Packer installer script to create Marathon's Jenkins AMI.

Get packer from https://www.packer.io/
Documentation on provisioning new AMI for jenkins: https://wiki.mesosphere.com/display/MARATHON/Provision+New+AMI+for+Jenkins+Node
Mesos version used by marathon: https://github.com/mesosphere/marathon/blob/master/project/Dependencies.scala#L87

Here is an example on how to install mesos 1.5.0 using maws (https://github.com/mesosphere/maws#osx--macos) which will result in image named 'JenkinsMarathonCI-Debian8-2018-02-02'

```bash
$(maws login "Team 10")
AWS_PROFILE=273854932432_Mesosphere-PowerUser packer build -color -var 'ami_name=JenkinsMarathonCI-Debian8-2018-02-02' -var 'mesos_version=1.5.0-2.0.1' marathon-jenkins-ami.json
```
