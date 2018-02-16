import os
import uuid

from dcos import http, util, mesos
from dcos.errors import DCOSException


def make_id(prefix=None):
    random_part = uuid.uuid4().hex
    if prefix is None:
        return random_part
    return '/{}-{}'.format(prefix, random_part)


# should be in shakedown
def get_resource(resource):
    """:param resource: optional filename or http(s) url for the application or group resource
       :type resource: str
       :returns: resource
       :rtype: dict
    """

    if resource is None:
        return None

    if os.path.isfile(resource):
        with util.open_file(resource) as resource_file:
                return util.load_json(resource_file)
    else:
        try:
            http.silence_requests_warnings()
            req = http.get(resource)
            if req.status_code == 200:
                data = b''
                for chunk in req.iter_content(1024):
                    data += chunk
                return util.load_jsons(data.decode('utf-8'))
            else:
                raise Exception
        except Exception:
            raise DCOSException("Can't read from resource: {0}. Please check that it exists.".format(resource))


class FaultDomain:
    """
    High-level representation of a fault domain
    """
    def __init__(self, config):
        # Make sure config is a dict
        if not type(config) is dict:
            config = {}

        # Extract fault domain
        fault_domain = config.get('fault_domain', {})

        # Extract zone/region
        self.zone = fault_domain.get('zone', {}).get('name', 'default')
        self.region = fault_domain.get('region', {}).get('name', 'default')


def get_cluster_local_domain():
    """Contacts the DC/OS mesos master and returns it's faultDomain configuration (aka "local domain")
    """
    master = mesos.get_master()
    return FaultDomain(master.state().get('domain', {}))


def get_cluster_agent_domains():
    """Returns a dictionary with the slave IDs in the cluster and their corresponding
       fault domain information
    """
    slave_domains = {}

    # Populate slave_domains with the ID and the corresponding domain for each slave
    master = mesos.get_master()
    for slave in master.slaves():
        slave_domains[slave['id']] = FaultDomain(slave._short_state.get('domain', None))
    return slave_domains


def get_all_cluster_regions():
    """Returns a dictionary with all the regions and their zones in the cluster
    """
    domain_regions = {}

    # Populate all the domain zones and regions found in the cluster
    for domain in get_cluster_agent_domains().values():
        if domain.region not in domain_regions:
            domain_regions[domain.region] = []
        if domain.zone not in domain_regions[domain.region]:
            domain_regions[domain.region].append(domain.zone)

    # Return dictionary
    return domain_regions


def get_biggest_cluster_region():
    """Returns a tuple with the name of the biggest region in the cluster and the zones in it
    """
    biggest_region = None
    biggest_region_zones = []

    for (region, zones) in get_all_cluster_regions().items():
        if len(zones) > len(biggest_region_zones):
            biggest_region_zones = zones
            biggest_region = region

    return (biggest_region, biggest_region_zones)


def get_app_domains(app):
    """Returns a list of all te fault domains used by the tasks of the specified app
    """
    tasks = app.get('tasks', [])
    slave_domains = get_cluster_agent_domains()

    assert len(tasks) > 0, "App %s did not launch any tasks on mesos" % (app['id'],)

    # Collect the FaultDomain objects from all the agents where the tasks are running
    domains = []
    for task in tasks:
        domains.append(slave_domains[task['slaveId']])

    return domains


def get_used_regions_and_zones(domains):
    """Returns tuple with the sets of the used regions and zones from the given list
       of FaultDomain object instances (ex. obtained by get_app_domains())"""
    used_regions = set()
    used_zones = set()

    for domain in domains:
        used_regions.add(domain.region)
        used_zones.add(domain.zone)

    return (used_regions, used_zones)


def count_agents_in_faultdomains(regions=None, zones=None):
    """Return the number of agents that belong on the specified region and/or zone
    """
    counter = 0

    # Make sure to always operate on iterable
    if type(regions) is str:
        regions = [regions]
    if type(zones) is str:
        zones = [zones]

    # Increment counter for the agents that pass the checks
    for domain in get_cluster_agent_domains().values():
        if regions is not None and domain.region not in regions:
            continue
        if zones is not None and domain.zone not in zones:
            continue
        counter += 1

    return counter
