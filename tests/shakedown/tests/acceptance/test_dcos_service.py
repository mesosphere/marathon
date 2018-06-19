import time

from shakedown import *


def test_get_service_framework_id():
    framework_id = get_service_framework_id('marathon')
    print('framework_id: ' + framework_id)
    assert framework_id is not None


def test_get_service_tasks():
    service_tasks = get_service_tasks('marathon')
    assert service_tasks is not None


def test_get_service_task():
    service_task = get_service_task('marathon', 'jenkins')
    assert service_task is not None


def test_get_service_ips():
    # Get all IPs associated with the 'jenkins' task running in the 'marathon' service
    service_ips = get_service_ips('marathon', 'jenkins')
    assert service_ips is not None
    print('service_ips: ' + str(service_ips))


def test_service_healthy():
    assert service_healthy('jenkins')
