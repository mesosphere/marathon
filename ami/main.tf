module "dcos-vpc" {
  source  = "dcos-terraform/vpc/aws"
  version = "~> 0.2.0"

  cluster_name = "production"
  availability_zones = ["us-east-1b"]
  subnet_range = "172.16.0.0/16"
}

output "vpc_id" {
  value = "${module.dcos-vpc.vpc_id}"
}

output "subnet_ids" {
  value = "${module.dcos-vpc.subnet_ids}"
}
