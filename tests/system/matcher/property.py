from precisely import Matcher
from precisely.coercion import to_matcher
from precisely.results import matched, unmatched, indented_list


class Prop(Matcher):

    def __init__(self, path, matcher):
        self.matcher = matcher
        self._path = path

    def readable_path(self):
        readable_path = ']['.join(str(p) for p in self._path)
        return '[{}]'.format(readable_path)

    def _get_value(self, item, path):
        if not path:
            return item
        elif not item:
            return None
        else:
            head_value = item.get(path[0])
            tail_path = path[1:]
            if not tail_path:
                return head_value
            else:
                return self._get_value(head_value, tail_path)

    def match(self, item):
        actual = self._get_value(item, self._path)
        if actual:
            result = self.matcher.match(actual)
            if result.is_match:
                return result
            else:
                explanation = "property {} {}".format(self.readable_path(), result.explanation)
                return unmatched(explanation)
        else:
            return unmatched("had no property {}".format(self.readable_path()))

    def describe(self):
        return "property {} {}".format(self.readable_path(), self.matcher.describe())


def prop(path, matcher):
    return Prop(path, matcher)


def has_value(name, matcher):
    return HasValue(name, to_matcher(matcher))


class HasValue(Matcher):
    def __init__(self, name, matcher):
        self._name = name
        self._matcher = matcher

    def match(self, actual):
        if self._name not in actual:
            return unmatched("was missing value '{0}'".format(self._name))
        else:
            actual_value = actual.get(self._name)
            property_result = self._matcher.match(actual_value)
            if property_result.is_match:
                return matched()
            else:
                return unmatched("value '{0}' {1}".format(self._name, property_result.explanation))

    def describe(self):
        return "object with value {0}: {1}".format(self._name, self._matcher.describe())


def has_values(**kwargs):
    return HasValues(kwargs.items())


class HasValues(Matcher):
    def __init__(self, matchers):
        self._matchers = [
            has_value(name, matcher)
            for name, matcher in matchers
        ]

    def match(self, actual):
        for matcher in self._matchers:
            result = matcher.match(actual)
            if not result.is_match:
                return result
        return matched()

    def describe(self):
        return "object with values:{0}".format(indented_list(
            "{0}: {1}".format(matcher._name, matcher._matcher.describe())
            for matcher in self._matchers
        ))
