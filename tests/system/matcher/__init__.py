from .eventually import eventually
from .message import has_failure_message

# py.test integration; hide these frames from tracebacks
__tracebackhide__ = True

__all__ = [
    "assert_that",
    "has_failure_message",
    "eventually"
]


def assert_that(value, matcher):
    result = matcher.match(value)
    if not result.is_match:
        raise AssertionError("\nExpected: {0}\nbut: {1}".format(matcher.describe(), result.explanation))
