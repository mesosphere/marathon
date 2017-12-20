import common
import json
import os.path
import pytest
import requests
import sseclient
import shakedown
import time
from datetime import timedelta
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


@pytest.fixture(scope="function")
def events():

    print("entering events fixture")

    url = urljoin(shakedown.dcos_url(), 'service/marathon/v2/events')
    headers = {'Authorization': 'token={}'.format(shakedown.dcos_acs_token()),
               'Accept': 'text/event-stream'}
    print('Query {} for events'.format(url))

    # Timeouts in seconds
    connect = 5
    until_first_event = 20

    with requests.get(url, headers=headers, stream=True, verify=False, timeout(connect, until_first_event)) as response:
        print('Connected to {}'.format(url))
        client = sseclient.SSEClient(response)
        print('Created SSE Client.')

        # We yield a generator of the parsed events to the test. Note: This must be lazy.
        yield (json.loads(event.data) for event in client.events())

        client.close()

    print("exiting events fixture")


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
