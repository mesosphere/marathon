from shakedown import *

from utils import file_dir

# services = mesos.get_master().frameworks(inactive=False, completed=False)
#
# for service in services:
#     print(service["name"])

# list of nodes
# copy file
# execute file

private_agents = get_private_agents()

for agent in private_agents:
    print(agent)
    stop_agent(agent)
    copy_file(agent, "{}/over-provision.sh".format(file_dir()))
    run_command(agent, "sh over-provision.sh")
    run_command(agent, "sudo rm -f /var/lib/mesos/slave/meta/slaves/latest")
    start_agent(agent)
