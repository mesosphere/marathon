from shakedown import *


def test_get_resources():
    resources = get_resources()

    assert resources.cpus > 0
    assert resources.mem > 0


def test_get_used_resources():
    unused = get_resources()
    used = get_used_resources()
    available = available_resources()

    expected = unused - used

    assert available == expected


def test_get_reserved_resources():
    resources = get_reserved_resources()
    assert resources.cpus > 0
    assert resources.mem > 0


def test_resources_needed():
    needed = resources_needed(8, 1, 2)

    assert needed.cpus == 8
    assert needed.mem == 16
