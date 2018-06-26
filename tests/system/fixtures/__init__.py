import aiohttp
import common
import json
import os.path
import pytest
import shakedown
import ssl
from datetime import timedelta
from pathlib import Path
from sseclient.async import SSEClient
from urllib.parse import urljoin


def fixtures_dir():
    return os.path.dirname(os.path.abspath(__file__))


@pytest.fixture(scope="function")
def wait_for_marathon_and_cleanup():
    print("entering wait_for_marathon_and_cleanup fixture")
    shakedown.wait_for_service_endpoint('marathon', timedelta(minutes=5).total_seconds())
    yield
    shakedown.wait_for_service_endpoint('marathon', timedelta(minutes=5).total_seconds())
    common.clean_up_marathon()
    print("exiting wait_for_marathon_and_cleanup fixture")


@pytest.fixture(scope="function")
def wait_for_marathon_user_and_cleanup():
    print("entering wait_for_marathon_user_and_cleanup fixture")
    shakedown.wait_for_service_endpoint('marathon-user', timedelta(minutes=5).total_seconds())
    with shakedown.marathon_on_marathon():
        yield
        shakedown.wait_for_service_endpoint('marathon-user', timedelta(minutes=5).total_seconds())
        common.clean_up_marathon()
    print("exiting wait_for_marathon_user_and_cleanup fixture")


def get_ssl_context():
    """Looks for the DC/OS certificate in the fixtures folder.

    Returns:
        None if ca file does not exist.
        SSLContext with file.

    """
    cafile = Path(fixtures_dir(), 'dcos-ca.crt')
    if cafile.is_file():
        print(f'Provide certificate {cafile}') # NOQA E999
        ssl_context = ssl.create_default_context(cafile=cafile)
        return ssl_context
    else:
        return None


@pytest.fixture
async def sse_events():
    url = urljoin(shakedown.dcos_url(), 'service/marathon/v2/events')
    headers = {'Authorization': 'token={}'.format(shakedown.dcos_acs_token()),
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
    print("entering user_billy fixture")
    shakedown.add_user('billy', 'billy')
    shakedown.set_user_permission(rid='dcos:adminrouter:service:marathon', uid='billy', action='full')
    shakedown.set_user_permission(rid='dcos:service:marathon:marathon:services:/', uid='billy', action='full')
    yield
    shakedown.remove_user_permission(rid='dcos:adminrouter:service:marathon', uid='billy', action='full')
    shakedown.remove_user_permission(rid='dcos:service:marathon:marathon:services:/', uid='billy', action='full')
    shakedown.remove_user('billy')
    print("exiting user_billy fixture")


@pytest.fixture(scope="function")
def docker_ipv6_network_fixture():
    agents = shakedown.get_agents()
    network_cmd = f"sudo docker network create --driver=bridge --ipv6 --subnet=fd01::/64 mesos-docker-ipv6-test"
    for agent in agents:
        shakedown.run_command_on_agent(agent, network_cmd)
    yield
    for agent in agents:
        shakedown.run_command_on_agent(agent, f"sudo docker network rm mesos-docker-ipv6-test")


@pytest.fixture(autouse=True, scope='session')
def archive_sandboxes():
    # Nothing to setup
    yield
    print('>>> Archiving Mesos sandboxes')
    # We tarball the sandboxes from all the agents first and download them afterwards
    for agent in shakedown.get_private_agents():
        file_name = 'sandbox_{}.tar.gz'.format(agent.replace(".", "_"))
        cmd = 'sudo tar --exclude=provisioner -zcf {} /var/lib/mesos/slave'.format(file_name)
        status, output = shakedown.run_command_on_agent(agent, cmd)  # NOQA

        if status:
            shakedown.copy_file_from_agent(agent, file_name)
        else:
            print('DEBUG: Failed to tarball the sandbox from the agent={}, output={}'.format(agent, output))
