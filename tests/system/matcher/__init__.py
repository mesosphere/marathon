from .eventually import eventually
from .property import prop
from precisely import has_feature

# py.test integration; hide these frames from tracebacks
__tracebackhide__ = True


def has_len(matcher):
    return has_feature("len", len, matcher)


__all__ = [
    "assert_that",
    "eventually",
    "has_len",
    "prop"
]


def assert_that(value, matcher):
    result = matcher.match(value)
    if not result.is_match:
        raise AssertionError("\nExpected: {0}\nbut: {1}".format(matcher.describe(), result.explanation))
