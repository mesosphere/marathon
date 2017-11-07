import time

from shakedown import *

from utils import file_dir


private_agents = sorted(get_private_agents())
count = 0

for agent in private_agents:
    count = count + 1
    if count > 0 :
        print(agent)
        copy_file(agent, "{}/over-provision.sh".format(file_dir()))
        run_command(agent, "sh over-provision.sh")
        stop_agent(agent)
        run_command(agent, "sudo rm -f /var/lib/mesos/slave/meta/slaves/latest")
        try:
            start_agent(agent)
        except Exception:
            time.sleep(1)
            start_agent(agent)
