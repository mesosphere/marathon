Marathon Jenkins AMI
====================

Packer installer script to create Marathon's Jenkins AMI.

Get packer from https://www.packer.io/

```bash
packer build -color -var 'aws_access_key=%AWS_ACCESS_KEY' -var 'aws_secret_key=%AWS_SECRET_KEY' -var 'ami_name=%AMI_NAME' marathon-jenkins-ami.json
```
