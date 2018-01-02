Marathon Jenkins AMI
====================

Packer installer script to create Marathon's Jenkins AMI.

Get packer from https://www.packer.io/
Documentation on provisioning new AMI for jenkins: https://wiki.mesosphere.com/display/MARATHON/Provision+New+AMI+for+Jenkins+Node
Mesos version used by marathon: https://github.com/mesosphere/marathon/blob/master/project/Dependencies.scala#L87

```bash
AWS_PROFILE=%AWS_PROFILE packer build -color \
    -var 'ami_name=%AMI_NAME' \
    -var 'mesos_version=%MESOS_VERSION' \
    marathon-jenkins-ami.json
```
