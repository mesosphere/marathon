import time
import traceback

from dcos.mesos import DCOSClient
from dcos import mesos
from shakedown import *
from utils import *


def app(id=1, instances=1):
    app_json = {
      "id": "",
      "instances":  1,
      "cmd": "for (( ; ; )); do sleep 100000000; done",
      "cpus": 0.01,
      "mem": 32,
      "disk": 0
    }
    if not str(id).startswith("/"):
        id = "/" + str(id)
    app_json['id'] = id
    app_json['instances'] = instances

    return app_json


def group(gcount=1, instances=1):
    id = "/2deep/group"
    group = {
        "id": id,
        "apps": []
    }

    for num in range(1, gcount + 1):
        app_json = app(id + "/" + str(num), instances)
        group['apps'].append(app_json)

    return group


def constraints(name, operator, value=None):
    constraints = [name, operator]
    if value is not None:
        constraints.append(value)
    return [constraints]


def unique_host_constraint():
    return constraints('hostname', 'UNIQUE')


def delete_all_apps():
    client = marathon.create_client()
    client.remove_group("/", True)


def time_deployment(test=""):
    client = marathon.create_client()
    start = time.time()
    deployment_count = 1
    while deployment_count > 0:
        # need protection when tearing down
        try:
            deployments = client.get_deployments()
            deployment_count = len(deployments)
            if deployment_count > 0:
                time.sleep(1)
        except:
            wait_for_service_endpoint('marathon-user')
            pass

    end = time.time()
    elapse = round(end - start, 3)
    return elapse


def delete_group(group="/2deep/group"):
    client = marathon.create_client()
    client.remove_group(group, True)


def delete_group_and_wait(group="test"):
    delete_group(group)
    time_deployment("undeploy")


def deployment_less_than_predicate(count=10):
    client = marathon.create_client()
    return len(client.get_deployments()) < count


def launch_apps(count=1, instances=1):
    client = marathon.create_client()
    for num in range(1, count + 1):
        # after 400 and every 50 check to see if we need to wait
        if num > 400 and num % 50 == 0:
            deployments = len(client.get_deployments())
            if deployments > 30:
                # wait for deployment count to be less than a sec
                wait_for(deployment_less_than_predicate)
                time.sleep(1)
        client.add_app(app(num, instances))


def launch_group(count=1, instances=1):
    client = marathon.create_client()
    client.create_group(group(count, instances))


def delete_all_apps_wait():
    delete_all_apps()
    time_deployment("undeploy")


def scale_test_apps(test_obj):
    if 'instance' in test_obj.style:
        scale_test_app(test_obj)
    if 'count' in test_obj.style:
        count_test_app(test_obj)
    if 'group' in test_obj.style:
        group_test_app(test_obj)


def get_current_tasks():
    return len(get_tasks())


def get_current_app_tasks(starting_tasks):
    return len(get_tasks()) - starting_tasks


def count_test_app(test_obj):
    # make sure no apps currently
    delete_all_apps_wait2()

    test_obj.start = time.time()
    starting_tasks = get_current_tasks()

    # launch and
    launch_complete = True
    try:
        launch_apps2(test_obj)
    except:
        test_obj.add_event('Failure to fully launch')
        launch_complete = False
        pass

    # time to finish launch
    try:
        time_deployment2(test_obj, starting_tasks)
    except Exception as e:
        assert False

    current_tasks = get_current_app_tasks(starting_tasks)
    test_obj.add_event('undeploying {} tasks'.format(current_tasks))

    # delete apps
    delete_all_apps_wait2(test_obj)

    assert launch_complete


def launch_apps2(test_obj):
    client = marathon.create_client()
    count = test_obj.count
    instances = test_obj.instance

    for num in range(1, count + 1):
        # after 400 and every 50 check to see if we need to wait
        if num > 400 and num % 50 == 0:
            deployments = len(client.get_deployments())
            if deployments > 30:
                # wait for deployment count to be less than a sec
                wait_for(deployment_less_than_predicate)
                time.sleep(1)
        client.add_app(app(num, instances))


def scale_test_app(test_obj):

    # make sure no apps currently
    delete_all_apps_wait2()

    test_obj.start = time.time()
    starting_tasks = get_current_tasks()
    # launch apps
    launch_complete = True
    try:
        launch_apps2(test_obj)
    except:
        test_obj.failed('Failure to launched (but we still will wait for deploys)')
        launch_complete = False
        pass

    # time launch
    try:
        time_deployment2(test_obj, starting_tasks)
    except Exception as e:
        assert False

    current_tasks = get_current_app_tasks(starting_tasks)
    test_obj.add_event('undeploying {} tasks'.format(current_tasks))

    # delete apps
    delete_all_apps_wait2(test_obj)

    assert launch_complete


def group_test_app(test_obj):

    # make sure no apps currently
    try:
        delete_all_apps_wait2()
    except:
        pass

    test_obj.start = time.time()
    starting_tasks = get_current_tasks()
    count = test_obj.count
    instances = test_obj.instance

    # launch apps
    launch_complete = True
    try:
        launch_group(count, instances)
    except:
        test_obj.failed('Failure to launched (but we still will wait for deploys)')
        launch_complete = False
        pass

    # time launch
    try:
        time_deployment2(test_obj, starting_tasks)
        launch_complete = True
    except Exception as e:
        assert False

    current_tasks = get_current_app_tasks(starting_tasks)
    test_obj.add_event('undeploying {} tasks'.format(current_tasks))

    # delete apps
    delete_all_apps_wait2(test_obj)

    assert launch_complete


def delete_all_apps_wait2(test_obj=None, msg='undeployment failure'):

    try:
        delete_all_apps()
    except Exception as e:
        if test_obj is not None:
            test_obj.add_event(msg)
        pass

    # some deletes (group test deletes commonly) timeout on remove_app
    # however it is a marathon internal issue on getting a timely response
    # all tested situations the remove did succeed
    try:
        wait_for_service_endpoint('marathon-user')
        undeployment_wait(test_obj)
    except Exception as e:
        test_obj.add_event(msg)
        assert False, msg


def undeployment_wait(test_obj=None):
    client = marathon.create_client()
    start = time.time()
    deployment_count = 1
    failure_count = 0
    while deployment_count > 0:
        # need protection when tearing down
        try:
            deployments = client.get_deployments()
            deployment_count = len(deployments)

            if deployment_count > 0:
                time.sleep(1)
                failure_count = 0
        except:
            failure_count += 1
            # consecutive failures great than x
            if failure_count > 10 and test_obj is not None:
                test_obj.failed('Too many failures waiting for undeploy')
                raise TestException()

            time.sleep(3)
            wait_for_service_endpoint('marathon-user')
            pass

    if test_obj is not None:
        test_obj.undeploy_complete(start)


def time_deployment2(test_obj, starting_tasks):
    client = marathon.create_client()
    target_tasks = starting_tasks + (test_obj.count * test_obj.instance)
    current_tasks = 0

    deployment_count = 1
    failure_count = 0
    while deployment_count > 0:
        # need protection when tearing down
        try:
            deployments = client.get_deployments()
            deployment_count = len(deployments)
            current_tasks = get_current_app_tasks(starting_tasks)

            if deployment_count > 0:
                time.sleep(1)
                failure_count = 0
        except:
            failure_count += 1
            # consecutive failures > x will fail test
            if failure_count > 10:
                test_obj.failed('Too many failures query for deployments')
                raise TestException()

            wait_for_service_endpoint('marathon-user')
            pass

    test_obj.successful()


def scale_apps(count=1, instances=1):
    test = "scaling apps: " + str(count) + " instances " + str(instances)

    start = time.time()
    launch_apps(count, instances)
    complete = False
    while not complete:
        try:
            time_deployment(test)
            complete = True
        except:
            time.sleep(2)
            pass

    launch_time = elapse_time(start, time.time())
    delete_all_apps_wait()
    return launch_time


def scale_groups(count=2):
    test = "group test count: " + str(instances)
    start = time.time()
    try:
        launch_group(count)
    except:
        # at high scale this will timeout but we still
        # want the deployment time
        pass

    time_deployment(test)
    launch_time = elapse_time(start, time.time())
    delete_group_and_wait("test")
    return launch_time


def elapse_time(start, end=None):
    if end is None:
        end = time.time()
    return round(end-start, 3)


def write_meta_data(test_metadata={}, filename='meta-data.json'):
    agents = get_private_agents()
    resources = available_resources()
    metadata = {
        'dcos-version': dcos_version(),
        'private-agents': len(agents),
        'resources': {
            'cpus': resources.cpus,
            'memory': resources.mem
        }
    }

    metadata.update(test_metadata)
    with open(filename, 'w') as out:
        json.dump(metadata, out)

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


def get_mom_json(version='v1.3.6'):
    mom_json = get_resource("mom.json")
    docker_image = "mesosphere/marathon:{}".format(version)
    mom_json['container']['docker']['image'] = docker_image
    mom_json['labels']['DCOS_PACKAGE_VERSION'] = version
    return mom_json


def install_mom(version='v1.3.6'):
    # the docker tags start with v
    # however the marathon reports version without the v :(
    if not version.startswith('v'):
        version = 'v{}'.format(version)

    client = marathon.create_client()
    client.add_app(get_mom_json(version))
    print("Installing MoM: {}".format(version))
    deployment_wait()


def uninstall_mom():
    try:
        framework_id = get_service_framework_id('marathon-user')
        if framework_id is not None:
            print('uninstalling: {}'.format(framework_id))
            dcos_client = mesos.DCOSClient()
            dcos_client.shutdown_framework(framework_id)
            time.sleep(2)
    except:
        pass

    removed = False
    max_times = 10
    while not removed:
        try:
            max_times =- 1
            client = marathon.create_client()
            client.remove_app('marathon-user')
            deployment_wait()
            time.sleep(2)
            removed = True
        except DCOSException:
            # remove_app throws DCOSException if it doesn't exist
            removed = True
            pass
        except Exception:
            # http or other exception and we retry
            traceback.print_exc()
            time.sleep(5)
            if max_time > 0:
                pass

    delete_zk_node('universe/marathon-user')


def ensure_test_mom(test_obj):
    valid = ensure_mom_version(test_obj.mom_version)
    if not valid:
        test_obj.failed('Unable to install mom')

    return valid


def ensure_mom_version(version):
    if not is_mom_version(version):
        try:
            uninstall_mom()
            install_mom(version)
            wait_for_service_endpoint('marathon-user', 1200)
        except Exception as e:
            traceback.print_exc()
            return False
    return True


def is_mom_version(version):
    same_version = False
    max_times = 10
    check_complete = False
    while not check_complete:
        try:
            max_times == 1
            with marathon_on_marathon():
                client = marathon.create_client()
                about = client.get_about()
                same_version = version == about.get("version")
                check_complete = True
        except DCOSException:
            # if marathon doesn't exist yet
            pass
            return False
        except Exception as e:
            if max_times > 0:
                pass
                # this failure only happens at very high scale
                # it takes a lot of time to recover
                wait_for_service_endpoint('marathon-user', 600)
            else:
                return False
    return same_version


class Resources(object):

    cpus = 0
    mem = 0

    def __init__(self, cpus=0, mem=0):
        self.cpus = cpus
        self.mem = mem

    def __str__(self):
        return "cpus: {}, mem: {}".format(self.cpus, self.mem)

    def __repr__(self):
        return "cpus: {}, mem: {}".format(self.cpus, self.mem)

    def __sub__(self, other):
        total_cpu = self.cpus - other.cpus
        total_mem = self.mem - other.mem

        return Resources(total_cpu, total_mem)

    def __rsub__(self, other):
        return self.__sub__(other)

    def __gt__(self, other):
        return self.cpus > other.cpus and self.mem > other.cpus

    def __mul__(self, other):
        return Resources(self.cpus * other, self.mem * other)

    def __rmul__(self, other):
        return Resources(self.cpus * other, self.mem * other)


def get_resources(rtype='resources'):
    """ resource types from summary include:  resources, used_resources
    offered_resources, reserved_resources, unreserved_resources
    This current returns resources
    """
    cpus = 0
    mem = 0
    summary = DCOSClient().get_state_summary()

    if 'slaves' in summary:
        agents = summary.get('slaves')
        for agent in agents:
            cpus += agent[rtype]['cpus']
            mem += agent[rtype]['mem']

    return Resources(cpus, mem)


def available_resources():
    res = get_resources()
    used = get_resources('used_resources')

    return res - used


class ScaleTest(object):

    name = ''
    # app, pod
    under_test = ''
    # instance, group, count
    style = ''

    instance = 1
    count = 1
    # successful, failed, skipped
    status = 'running'
    deploy_time = None
    undeploy_time = None

    def __init__(self, name, mom, under_test, style, count, instance):
        self.name = name
        self.under_test = under_test
        self.style = style
        self.instance = int(instance)
        self.count = int(count)
        self.start = time.time()
        self.mom = mom
        self.events = []

    def __str__(self):
        return "test: {} status: {} time: {} events: {}".format(
            self.name,
            self.status,
            self.deploy_time,
            len(self.events))

    def __repr__(self):
        return "test: {} status: {} time: {} events: {}".format(
            self.name,
            self.status,
            self.deploy_time,
            len(self.events))

    def add_event(self, eventInfo):
        self.events.append('    event: {} (time in test: {})'.format(eventInfo, elapse_time(self.start)))

    def _status(self, status):
        """ end of scale test, however still may have events like undeploy_time
        this marks the end of the test time
        """
        self.status = status
        if 'successful' == status:
            self.deploy_time = elapse_time(self.start)
        else:
            self.deploy_time = 'x'

    def successful(self):
        self.add_event('successful')
        self._status('successful')

    def failed(self, reason="unknown"):
        self.add_event('failed: {}'.format(reason))
        self._status('failed')

    def skip(self, reason="unknown"):
        self.add_event('skipped: {}'.format(reason))
        self._status('skipped')

    def undeploy_complete(self, start):
        self.add_event('undeployment complete')
        self.undeploy_time = elapse_time(start)

    def log_events(self):
        for event in self.events:
            print(event)

    def log_stats(self):
        print('    *status*: {}, deploy: {}, undeploy: {}'.format(self.status, self.deploy_time, self.undeploy_time))


def start_test(name, marathons=None):
    """ test name example: test_mom1_apps_instances_1_100
    with list of marathons to test against.  If marathons are None, the root marathon is tested.
    """
    test = ScaleTest(name, *name.split("_")[1:])
    if marathons is None:
        test.mom_version = 'root'
    else:
        test.mom_version = marathons[test.mom]
    return test


def resource_need(instances=1, counts=1, app_cpu=0.01, app_mem=1):
    total_tasks = instances * counts
    total_cpu = app_cpu * total_tasks
    total_mem = app_mem * total_tasks
    return Resources(total_cpu, total_mem)


def scaletest_resources(test_obj):
    return resource_need(test_obj.instance,
                         test_obj.count)
