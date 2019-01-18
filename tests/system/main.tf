module "dcos" {
  source  = "dcos-terraform/dcos/aws"
  version = "~> 0.1"

  cluster_name = "${var.cluster_name}"
  admin_ips    = ["${data.http.whatismyip.body}/32"]

  num_masters        = "3"
  num_private_agents = "3"
  num_public_agents  = "1"

  dcos_version = "1.12.0"

  dcos_variant = "${var.dcos_variant}"

  dcos_aws_region = "us-west-2"

  dcos_instance_os             = "centos_7.5"
  bootstrap_instance_type      = "m4.larger"
  masters_instance_type        = "m4.large"
  private_agents_instance_type = "m4.large"
  public_agents_instance_type  = "m4.large"
}

variable "dcos_variant" {
  description = "Specifies whether this is an open, permissive or strict cluster."
  default     = "open"
}

variable "cluster_name" {
  description = "The name of the created cluster."
}
