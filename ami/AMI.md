Marathon Jenkins AMI
====================

Packer installer script to create Marathon's Jenkins AMI.

Get packer from https://www.packer.io/
Documentation on provisioning new AMI for jenkins: https://wiki.mesosphere.com/display/MARATHON/Provision+New+AMI+for+Jenkins+Node
Mesos version used by marathon: https://github.com/mesosphere/marathon/blob/master/project/Dependencies.scala#L87

```bash
packer build -color \
    -var 'aws_access_key=%AWS_ACCESS_KEY' \
    -var 'aws_secret_key=%AWS_SECRET_KEY' \
    -var 'ami_name=%AMI_NAME' \
    -var 'conduit_token=%PHABRICATOR_CREDENTIALS' \
    -var 'mesos_version=%MESOS_VERSION' \
    marathon-jenkins-ami.json
```
