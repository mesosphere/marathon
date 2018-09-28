import os
import uuid

from collections import defaultdict
from shakedown import http, util
from shakedown.errors import DCOSException
from shakedown.clients import mesos
from os.path import join


def make_id(app_id_prefix=None, parent_group="/"):
    app_id = f'{app_id_prefix}-{uuid.uuid4().hex}' if app_id_prefix else str(uuid.uuid4().hex)
    return join(parent_group, app_id)


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

    This is a helper class, whose constructor automagically sanitizes the response
    it receives from `mesos.master().state().get('domain')` and wraps it into a more
    user-friendly object.

    That raw `domain` value in the state has the following structure:

    {
        "fault_domain": {
            "zone": {
                "name": "<zone-name>"
            },
            "region": {
                "name": "<region-name>"
            }
        }
    }

    """
    def __init__(self, config):
        # If `domain` is a string, None, or any unexpected value, treat it as
        # an empty domain configuration.
        if not isinstance(config, dict):
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
    return FaultDomain(master.state().get('domain'))


def get_cluster_agent_domains():
    """Returns a dictionary with the agent IDs in the cluster and their corresponding
       fault domain information
    """
    agent_domains = {}

    # Populate agent_domains with the ID and the corresponding domain for each agent
    master = mesos.get_master()
    for agent in master.slaves():
        agent_domains[agent['id']] = FaultDomain(agent._short_state.get('domain'))
    return agent_domains


def get_all_cluster_regions():
    """Returns a dictionary with all the regions and their zones in the cluster
    """
    domain_regions = defaultdict(list)

    # Populate all the domain zones and regions found in the cluster
    for domain in get_cluster_agent_domains().values():
        if domain.zone not in domain_regions[domain.region]:
            domain_regions[domain.region].append(domain.zone)

    # Return dictionary
    return domain_regions


def get_biggest_region(regions):
    """Returns a tuple with the name of the region with the most zones in the
       cluster and a list with the actual zones in it
    """
    return max(regions.items(), key=lambda kv: len(kv[1]))


def get_biggest_region_name(regions):
    """Returns the name of the region with the most zones in the cluster
    """
    return get_biggest_region(regions)[0]


def get_biggest_region_zones(regions):
    """Returns the list of zones on the region with the most zones in the cluster
    """
    return get_biggest_region(regions)[1]


def get_app_domains(app):
    """Returns a set of all the fault domains used by the tasks of the specified app
    """
    tasks = app.get('tasks', [])
    if len(tasks) == 0:
        return set()

    # Collect the FaultDomain objects from all the agents where the tasks are running
    agent_domains = get_cluster_agent_domains()
    return set(agent_domains[task['slaveId']] for task in tasks)


def get_used_regions_and_zones(domains):
    """Returns tuple with the sets of the used regions and zones from the given list
       of FaultDomain object instances (ex. obtained by get_app_domains())"""
    used_regions = set()
    used_zones = set()

    for domain in domains:
        used_regions.add(domain.region)
        used_zones.add(domain.zone)

    return (used_regions, used_zones)


def count(iterable, predicate):
    """Counts the items in the iterable that pass the given predicate"""
    return sum(1 for x in iterable if predicate(x))


def in_region(region):
    """A predicate for `count` that checks if the FaultDomain in the iterable
       is in the given region"""
    return lambda x: x.region == region


def in_zone(zone):
    """A predicate for `count` that checks if the FaultDomain in the iterable
       is in the given zone"""
    return lambda x: x.zone == zone


def in_region_and_zone(region, zone):
    """A predicate for `count` that checks if the FaultDomain in the iterable
       is in the given region AND zone"""
    return lambda x: (x.region == region) and (x.zone == zone)


def count_cluster_agent_domains(predicate):
    """Shorthand for `count(get_cluster_agent_domains().values(), predicate)`"""
    return count(get_cluster_agent_domains().values(), predicate)
