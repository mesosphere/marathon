module "dcos" {
  source  = "dcos-terraform/dcos/aws"
  version = "~> 0.1"

  # Cluster type and version
  cluster_name              = "${var.cluster_name}"
  dcos_version              = "master"
  custom_dcos_download_path = "${var.dcos_installer}"
  dcos_variant              = "${var.dcos_variant}"
  dcos_security             = "${var.dcos_security}"
  dcos_license_key_contents = "${var.dcos_license_key_contents}"

  # Access
  ssh_public_key_file = ""
  ssh_public_key      = "${var.ssh_public_key}"
  admin_ips           = "${var.admin_ips}"

  # Nodes
  num_masters        = "3"
  num_private_agents = "3"
  num_public_agents  = "1"

  dcos_instance_os             = "centos_7.5"
  bootstrap_instance_type      = "m4.large"
  masters_instance_type        = "m4.large"
  private_agents_instance_type = "m4.large"
  public_agents_instance_type  = "m4.large"

  dcos_rexray_config = <<EOF
  # YAML
    rexray:
      loglevel: info
      service: ebs
    libstorage:
      server:
        tasks:
          logTimeout: 5m
      integration:
        volume:
          operations:
            unmount:
              ignoreusedcount: true
  EOF
  dcos_dns_search                = "mesos"
  dcos_exhibitor_storage_backend = "static"
  dcos_master_discovery          = "static"
  dcos_resolvers                 = <<EOF
    - 8.8.4.4
    - 8.8.8.8
  EOF
}

variable "admin_ips" {
  type = "list"
}

variable "dcos_variant" {
  description = "Specifies whether this is an open, permissive or strict cluster."
  default     = "open"
}

variable "dcos_security" {
  description = "Specifies whether this is a scrict or permissive cluster. Defaults to empty for open DC/OS clusters."
  default     = ""
}

variable "dcos_license_key_contents" {
  description = "The DC/OS enterprise license to use. Defaults to empty for open DC/OS clusters."
  default     = ""
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

output "cluster_address" {
  value = "${module.dcos.masters-loadbalancer}"
}
