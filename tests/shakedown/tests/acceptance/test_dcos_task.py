from shakedown import *


def test_get_active_tasks():
    task_names = []
    for task in get_active_tasks():
        task_names.append(task['name'])

    assert 'jenkins' in task_names
