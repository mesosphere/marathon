from .eventually import eventually
from .property import prop

# py.test integration; hide these frames from tracebacks
__tracebackhide__ = True

__all__ = [
    "assert_that",
    "eventually",
    "prop"
]


def assert_that(value, matcher):
    result = matcher.match(value)
    if not result.is_match:
        raise AssertionError("\nExpected: {0}\nbut: {1}".format(matcher.describe(), result.explanation))
