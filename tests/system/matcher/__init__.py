from .eventually import has_eventually, failure_message

# py.test integration; hide these frames from tracebacks
__tracebackhide__ = True

__all__ = [
    "assert_that",
    "failure_message",
    "has_eventually"
]


def assert_that(value, matcher):
    result = matcher.match(value)
    if not result.is_match:
        raise AssertionError("\nExpected: {0}\nbut: {1}".format(matcher.describe(), result.explanation))
