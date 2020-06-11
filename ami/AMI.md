Marathon Jenkins AMI
====================

Packer installer script to create Marathon's Jenkins AMI.

## Prerequisites
You need

* [Packer](https://www.packer.io/)
* [Terraform](https://www.terraform.io/)
* [MAWS](https://github.com/mesosphere/maws)

## Provision AMI

Authenticate with the AWS account.

```
$(maws login "Team 10")
```

Create a VPC and subnet with

```
terraform init
terraform apply
```


Create the new AMI. Here is an example on how to install Mesos 1.9.0 which will result in image named 'JenkinsMarathonCI-Debian9-2018-02-02'

```bash
packer build -color \
  -var "ami_name=JenkinsMarathonCI-Debian9-$(date +%Y-%m-%d)" \
  -var 'mesos_version=1.9.0-2.0.1.debian9' \
  -var 'vpc_id=<id from terraform>' \
  -var 'subnet_id=<id from terraform>' \
  marathon-jenkins-ami.json
```

Destroy the VPC with

```
terraform destroy
```

Add the newly provisioned  AMI to Jenkins as documented in the [wiki](https://wiki.mesosphere.com/display/ENG/%5BMarathon%5D+Provision+New+AMI+for+Jenkins+Node).
