module "dcos" {
  source  = "dcos-terraform/dcos/aws"
  version = "~> 0.1"

  cluster_name        = "${var.cluster_name}"
  ssh_public_key_file = ""
  ssh_public_key      = "${var.ssh_public_key}"
  admin_ips           = "${var.admin_ips}"

  num_masters        = "3"
  num_private_agents = "3"
  num_public_agents  = "1"

  dcos_version = "1.12.0"
  custom_dcos_download_path = "${var.dcos_installer}"

  dcos_variant = "${var.dcos_variant}"

  dcos_aws_region = "us-west-2"

  dcos_instance_os             = "centos_7.5"
  bootstrap_instance_type      = "m4.large"
  masters_instance_type        = "m4.large"
  private_agents_instance_type = "m4.large"
  public_agents_instance_type  = "m4.large"
}

variable "admin_ips" {
  type = "list"
}

variable "dcos_variant" {
  description = "Specifies whether this is an open, permissive or strict cluster."
  default     = "open"
}

variable "cluster_name" {
  description = "The name of the created cluster."
}

variable "ssh_public_key" {
  description = "Path to the public key for the cluster."
}

variable "dcos_installer" {
  description = "Url to DC/OS installer."
}

# output "cluster_address" {
#  value = "${module.dcos.masters-loadbalancer}"
# }
