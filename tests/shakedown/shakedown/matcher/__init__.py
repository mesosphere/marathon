from .eventually import eventually
from .property import has_value, has_values, prop
from precisely import has_feature

# py.test integration; hide these frames from tracebacks
__tracebackhide__ = True


def has_len(matcher):
    """Match len of a value.

    assert_that([1, 2], has_len(2)) will pass but assert_that([1, 2], has_len(1))
    will fail with an assertion error.
    """
    return has_feature("len", len, matcher)


__all__ = [
    "assert_that",
    "eventually",
    "has_len",
    "has_value",
    "has_values",
    "prop"
]


def assert_that(value, matcher, custom_description=""):
    result = matcher.match(value)
    prefix = "" if not custom_description else "{}\n".format(custom_description)
    if not result.is_match:
        raise AssertionError("{}Expected: {}\nbut: {}".format(
            prefix, matcher.describe(), result.explanation))
