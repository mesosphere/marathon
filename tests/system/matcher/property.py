from precisely import Matcher
from precisely.results import unmatched

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
