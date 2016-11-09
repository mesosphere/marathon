""" """
from shakedown import *
from utils import *
from dcos.errors import DCOSException


def app(id=1, instances=1):
    app_json = {
      "id": "",
      "instances":  1,
      "cmd": "sleep 100000000",
      "cpus": 0.01,
      "mem": 1,
      "disk": 0
    }
    if not str(id).startswith("/"):
        id = "/" + str(id)
    app_json['id'] = id
    app_json['instances'] = instances

    return app_json


def constraints(name, operator, value=None):
    constraints = [name, operator]
    if value is not None:
      constraints.append(value)
    return [constraints]


def unique_host_constraint():
    return constraints('hostname', 'UNIQUE')


def group():

    return {
        "apps": [],
        "dependencies": [],
        "groups": [
            {
                "apps": [
                    {
                        "cmd": "sleep 1000",
                        "cpus": 1.0,
                        "dependencies": [],
                        "disk": 0.0,
                        "id": "/test-group/sleep/goodnight",
                        "instances": 1,
                        "mem": 128.0
                    },
                    {
                        "cmd": "sleep 1000",
                        "cpus": 1.0,
                        "dependencies": [],
                        "disk": 0.0,
                        "id": "/test-group/sleep/goodnight2",
                        "instances": 1,
                        "mem": 128.0
                    }
                ],
                "dependencies": [],
                "groups": [],
                "id": "/test-group/sleep",
            }
        ],
        "id": "/test-group"
    }


def python_http_app():
    return {
        'id': 'python-http',
        'cmd': '/opt/mesosphere/bin/python -m http.server $PORT0',
        'cpus': 1,
        'mem': 128,
        'disk': 0,
        'instances': 1
        }


def pin_to_host(app_def, host):
    app_def['constraints'] = constraints('hostname', 'LIKE', host)


def health_check(path='/', port_index=0, failures=1, timeout=2):

    return {
          'protocol': 'HTTP',
          'path': path,
          'timeoutSeconds': timeout,
          'intervalSeconds': 2,
          'maxConsecutiveFailures': failures,
          'portIndex': port_index
        }


def cluster_info(mom_name='marathon-user'):
    agents = get_private_agents()
    print("agents: {}".format(len(agents)))
    client = marathon.create_client()
    about = client.get_about()
    print("marathon version: {}".format(about.get("version")))
    # see if there is a MoM
    with marathon_on_marathon(mom_name):
        try:
            client = marathon.create_client()
            about = client.get_about()
            print("marathon MoM version: {}".format(about.get("version")))

        except Exception as e:
            print("Marathon MoM not present")


def delete_all_apps():
    client = marathon.create_client()
    apps = client.get_apps()
    for app in apps:
        if app['id'] == '/marathon-user':
            print('WARNING: marathon-user installed')
        else:
            client.remove_app(app['id'], True)


def delete_all_apps_wait():
    delete_all_apps()
    deployment_wait()


def deployment_wait(timeout=120):
    client = marathon.create_client()
    start = time.time()
    deployment_count = 1
    # TODO: time limit with fail
    while deployment_count > 0:
        deployments = client.get_deployments()
        deployment_count = len(deployments)
        end = time.time()
        elapse = round(end - start, 3)
        if elapse > timeout:
            raise DCOSException("timeout on deployment wait: {}".format(elapse))

    end = time.time()
    elapse = round(end - start, 3)
    return elapse


def ip_other_than_mom():
    mom_ip = ip_of_mom()

    agents = get_private_agents()
    for agent in agents:
        if agent != mom_ip:
            return agent

    return None


def ip_of_mom():
    service_ips = get_service_ips('marathon', 'marathon-user')
    for mom_ip in service_ips:
        return mom_ip


def ensure_mom():
    if not is_mom_installed():
        install_package_and_wait('marathon')
        deployment_wait()
        end_time = time.time() + 120
        while time.time() < end_time:
            if service_healthy('marathon-user'):
                break
            time.sleep(1)

        if not wait_for_service_url('marathon-user'):
            print('ERROR: Timeout waiting for endpoint')


def is_mom_installed():
    try:
        mom_ips = get_service_ips('marathon', "marathon-user")
    except Exception as e:
        return False
    else:
        return len(mom_ips) != 0


def restart_master_node():
    """ Restarts the master node
    """

    run_command_on_master("sudo /sbin/shutdown -r now")


def systemctl_master(command='restart'):
        run_command_on_master('sudo systemctl {} dcos-mesos-master'.format(command))



def wait_for_service_url(service_name, timeout_sec=120):
    """Checks the service url if available it returns true, on expiration
    it returns false"""

    future = time.time() + timeout_sec
    url = dcos_service_url(service_name)
    while time.time() < future:
        response = None
        try:
            response = http.get(url)
        except Exception as e:
            pass

        if response is None:
            time.sleep(5)
        elif response.status_code == 200:
            return True
        else:
            time.sleep(5)

    return False


def wait_for_service_url_removal(service_name, timeout_sec=120):
    """Checks the service url if it is removed it returns true, on expiration
    it returns false"""

    future = time.time() + timeout_sec
    url = dcos_service_url(service_name)
    while time.time() < future:
        response = None
        try:
            response = http.get(url)
        except Exception as e:
            return True

        time.sleep(5)

    return False


def save_iptables(host):
    run_command_on_agent(host, 'if [ ! -e iptables.rules ] ; then sudo iptables -L > /dev/null && sudo iptables-save > iptables.rules ; fi')


def restore_iptables(host):
    run_command_on_agent(host, 'if [ -e iptables.rules ]; then sudo iptables-restore < iptables.rules && rm iptables.rules ; fi')


def block_port(host, port, direction='INPUT'):
    run_command_on_agent(host, 'sudo iptables -I {} -p tcp --dport {} -j DROP'.format(direction, port))


def wait_for_task(service, task, timeout_sec=120):
    """Waits for a task which was launched to be launched"""

    now = time.time()
    future = now + timeout_sec

    while now < future:
        response = None
        try:
            response = get_service_task(service, task)
        except Exception as e:
            pass

        if response is not None and response['state'] == 'TASK_RUNNING':
            return response
        else:
            time.sleep(5)
            now = time.time()

    return None

def wait_for_task_health(service, task, timeout_sec=120):

    task = wait_for_task(service, task, timeout_sec)
