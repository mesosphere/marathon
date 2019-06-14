Marathon Jenkins AMI
====================

Packer installer script to create Marathon's Jenkins AMI.

Get packer from https://www.packer.io/
Documentation on provisioning new AMI for jenkins: https://wiki.mesosphere.com/display/ENG/%5BMarathon%5D+Provision+New+AMI+for+Jenkins+Node
Mesos version used by marathon: https://github.com/mesosphere/marathon/blob/b00f71136a7e35cb76c7df136d49b16b9ead2689/project/Dependencies.scala#L128

Here is an example on how to install Mesos 1.5.0 using maws (https://github.com/mesosphere/maws#osx--macos) which will result in image named 'JenkinsMarathonCI-Debian8-2018-02-02'

```bash
$(maws login "Team 10")
AWS_PROFILE=273854932432_Mesosphere-PowerUser packer build -color -var "ami_name=JenkinsMarathonCI-Debian9-$(date +%Y-%m-%d)" -var 'mesos_version=1.5.0-2.0.1' marathon-jenkins-ami.json
```

Here is an example on how to install Mesos 1.8.0 using [maws](https://github.com/mesosphere/maws#osx--macos) which will result in image named 'JenkinsMarathonCI-Debian9-2019-04-22'

```bash
$(maws login "Team 10")
AWS_PROFILE=273854932432_Mesosphere-PowerUser packer build -color -var "ami_name=JenkinsMarathonCI-Debian9-$(date +%Y-%m-%d)" -var 'mesos_version=1.8.0-2.0.6.debian9' marathon-jenkins-ami.json
```
