import common
import os.path
import pytest
import shakedown

from datetime import timedelta


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
def events_to_file():
    print("entering events_to_file fixture")
    shakedown.run_command_on_master('rm events.txt')

    # In strict mode marathon runs in SSL mode on port 8443 and requires authentication
    if shakedown.ee_version() == 'strict':
        shakedown.run_command_on_master(
            '(curl --compressed -H "Cache-Control: no-cache" -H "Accept: text/event-stream" ' +
            '-H "Authorization: token={}" '.format(shakedown.dcos_acs_token()) +
            '-o events.txt -k https://leader.mesos:8443/v2/events; echo $? > events.exitcode) &')

    # Otherwise marathon runs on HTTP mode on port 8080
    else:
        shakedown.run_command_on_master(
            '(curl --compressed -H "Cache-Control: no-cache" -H "Accept: text/event-stream" '
            '-o events.txt http://leader.mesos:8080/v2/events; echo $? > events.exitcode) &')

    yield
    shakedown.kill_process_on_host(shakedown.master_ip(), '[c]url')
    shakedown.run_command_on_master('rm events.txt')
    shakedown.run_command_on_master('rm events.exitcode')
    print("exiting events_to_file fixture")


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
