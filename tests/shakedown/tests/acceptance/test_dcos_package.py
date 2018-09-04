import json
import pytest

from shakedown import *

@pytest.fixture(scope='function')
def chronos_cleanup():
    yield
    remove_chronos()


def test_install_package_and_wait():
    assert not package_installed('chronos')
    install_package_and_wait('chronos')
    assert package_installed('chronos')


@pytest.mark.usefixtures("chronos_cleanup")
def test_uninstall_package_and_wait():
    if not package_installed('chronos'):
        install_package_and_wait('chronos')

    assert package_installed('chronos')
    uninstall_package_and_wait('chronos')
    delete_zk_node('chronos')
    assert package_installed('chronos') == False
    # this should only pass if we can install after the wait
    install_package_and_wait('chronos')

def task_cpu_predicate(service, task):
        try:
            response = get_service_task(service, task)
        except Exception as e:
            pass

        return (response is not None) and ('resources' in response) and ('cpus' in response['resources'])

@pytest.mark.usefixtures("chronos_cleanup")
def test_install_package_with_json_options():
    install_package_and_wait('chronos', None, 'big-chronos', None, {"chronos": {"cpus": 2}})
    wait_for(lambda: task_cpu_predicate('marathon', 'big-chronos'))
    assert get_service_task('marathon', 'big-chronos')['resources']['cpus'] == 2
    uninstall_package_and_wait('chronos', 'big-chronos')

def test_install_package_with_subcommand():
    install_package_and_wait('hello-world')
    stdout, stderr, return_code = run_dcos_command('hello-world --info')
    assert stdout.startswith('Hello-World')

def test_uninstall_package_with_subcommand():
    uninstall_package_and_wait('hello-world')
    stdout, stderr, return_code = run_dcos_command('hello-world --info')
    assert stderr.endswith("is not a dcos command.\n")

def test_add_package_repo():
    assert add_package_repo('Multiverse', 'https://github.com/mesosphere/multiverse/archive/version-2.x.zip', 0)

def test_get_package_repo():
    repos_json = get_package_repos()
    print(json.dumps(repos_json, sort_keys=True, indent=4, separators=(',', ': ')))
    assert repos_json['repositories'][0]['name'] == 'Multiverse'

def test_remove_package_repo():
    assert remove_package_repo('Multiverse')

def test_get_package_versions():
    versions = get_package_versions('marathon')
    assert '1.5.2' in versions

def remove_chronos():
    try:
        if package_installed('chronos'):
            uninstall_package_and_wait('chronos')
        delete_zk_node('chronos')
    except:
        pass

def setup_module(module):
    remove_chronos()
