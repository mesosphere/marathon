import aiohttp
import common
import json
import os.path
import pytest
import ssl
import logging

from datetime import timedelta
from pathlib import Path
from shakedown.dcos import cluster
from shakedown.clients import dcos_url_path
from shakedown.clients.authentication import dcos_acs_token
from shakedown.dcos.agent import get_agents, get_private_agents
from shakedown.dcos.command import run_command_on_agent
from shakedown.dcos.file import copy_file_from_agent
from shakedown.dcos.marathon import marathon_on_marathon
from shakedown.dcos.security import add_user, set_user_permission, remove_user, remove_user_permission
from asyncsseclient import SSEClient

logger = logging.getLogger(__name__)


def fixtures_dir():
    return os.path.dirname(os.path.abspath(__file__))


@pytest.fixture(scope="function")
def wait_for_marathon_and_cleanup():
    common.wait_for_service_endpoint('marathon', timedelta(minutes=5).total_seconds(), path="ping")
    yield
    common.wait_for_service_endpoint('marathon', timedelta(minutes=5).total_seconds(), path="ping")
    common.clean_up_marathon()


@pytest.fixture(scope="function")
def wait_for_marathon_user_and_cleanup():
    common.wait_for_service_endpoint('marathon-user', timedelta(minutes=5).total_seconds(), path="ping")
    with marathon_on_marathon() as client:
        yield
        common.wait_for_service_endpoint('marathon-user', timedelta(minutes=5).total_seconds(), path="ping")
        common.clean_up_marathon(client)


@pytest.fixture(scope="function")
def parent_group(request):
    """ Fixture which yields a temporary marathon parent group can be used to place apps/pods within the test
    function. Parent group will be removed after the test. Group name is equal to the test function name with
    underscores replaced by dashes.
    """
    group = '/{}'.format(request.function.__name__).replace('_', '-')
    yield group
    common.clean_up_marathon(parent_group=group)


def get_ca_file():
    return Path(fixtures_dir(), 'dcos-ca.crt')


def get_ssl_context():
    """Looks for the DC/OS certificate in the fixtures folder.

    Returns:
        None if ca file does not exist.
        SSLContext with file.

    """
    cafile = get_ca_file()
    if cafile.is_file():
        logger.info(f'Provide certificate {cafile}') # NOQA E999
        ssl_context = ssl.create_default_context(cafile=cafile)
        return ssl_context
    else:
        return None


@pytest.fixture
async def sse_events():
    url = dcos_url_path('service/marathon/v2/events')
    headers = {'Authorization': 'token={}'.format(dcos_acs_token()),
               'Accept': 'text/event-stream'}

    ssl_context = get_ssl_context()
    verify_ssl = ssl_context is not None
    async with aiohttp.ClientSession(headers=headers) as session:
        async with session.get(url, verify_ssl=verify_ssl, ssl_context=ssl_context) as response:
            async def internal_generator():
                client = SSEClient(response.content)
                async for event in client.events():
                    yield json.loads(event.data)

            yield internal_generator()


@pytest.fixture(scope="function")
def user_billy():
    logger.info("entering user_billy fixture")
    add_user('billy', 'billy')
    set_user_permission(rid='dcos:adminrouter:service:marathon', uid='billy', action='full')
    set_user_permission(rid='dcos:service:marathon:marathon:services:/', uid='billy', action='full')
    yield
    remove_user_permission(rid='dcos:adminrouter:service:marathon', uid='billy', action='full')
    remove_user_permission(rid='dcos:service:marathon:marathon:services:/', uid='billy', action='full')
    remove_user('billy')
    logger.info("exiting user_billy fixture")


@pytest.fixture(scope="function")
def docker_ipv6_network_fixture():
    agents = get_agents()
    network_cmd = f"sudo docker network create --driver=bridge --ipv6 --subnet=fd01::/64 mesos-docker-ipv6-test"
    for agent in agents:
        run_command_on_agent(agent, network_cmd)
    yield
    for agent in agents:
        run_command_on_agent(agent, f"sudo docker network rm mesos-docker-ipv6-test")


@pytest.fixture(autouse=True, scope='session')
def install_enterprise_cli():
    """Install enterprise cli on an DC/OS EE cluster before all tests start.
    """
    if cluster.ee_version() is not None:
        common.install_enterprise_cli_package()


@pytest.fixture(autouse=True, scope='session')
def archive_sandboxes():
    # Nothing to setup
    yield
    logger.info('>>> Archiving Mesos sandboxes')
    # We tarball the sandboxes from all the agents first and download them afterwards
    for agent in get_private_agents():
        file_name = 'sandbox_{}.tar.gz'.format(agent.replace(".", "_"))
        cmd = 'sudo tar --exclude=provisioner -zcf {} /var/lib/mesos/slave'.format(file_name)
        status, output = run_command_on_agent(agent, cmd)  # NOQA

        if status:
            copy_file_from_agent(agent, file_name)
        else:
            logger.warning('Failed to tarball the sandbox from the agent={}, output={}'.format(agent, output))
