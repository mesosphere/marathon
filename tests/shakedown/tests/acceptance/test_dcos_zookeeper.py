from shakedown import *


def test_delete_zk_node():
    install_package_and_wait('marathon')
    assert package_installed('marathon'), 'Package failed to install'

    end_time = time.time() + 120
    found = False
    while time.time() < end_time:
        found = get_service('marathon-user') is not None
        if found and service_healthy('marathon-user'):
            break
        time.sleep(1)

    assert found, 'Service did not register with DC/OS'

    task_names = []
    for task in get_active_tasks():
        task_names.append(task['name'])

    assert 'marathon-user' in task_names

    uninstall_package_and_wait('marathon')

    assert delete_zk_node('universe/marathon-user')


def get_zk_node_data():
    install_package_and_wait('marathon')
    get_zk_node_data('universe/marathon-user')


def teardown_module(module):
    clean_marathon()


def clean_marathon():
    try:
        uninstall_package_and_wait('marathon')
    except:
        pass

    delete_zk_node('universe/marathon-user')
