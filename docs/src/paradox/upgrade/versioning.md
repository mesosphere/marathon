---
title: Versioning
---

# Versioning

Starting with version 0.9.0 Marathon will adhere to [semantic versioning](http://semver.org).
That means we are committed to keep our documented REST API compatible across releases unless we change the MAJOR version
(the first number in the version tuple). If you depend on undocumented features, please tell us about them by [raising a GitHub issue](https://github.com/mesosphere/marathon/issues/new). API parts which we explicitly marked as EXPERIMENTAL are exempted from this rule. We will not introduce new features in PATCH version increments (the last number in the version tuple).

We might change the command line interfaces of the Marathon server process in rare cases in a MINOR version upgrade.
Please check the release notes for these.

Furthermore, we will provide release candidates for all new MAJOR/MINOR versions and invite our users to test them and
give us feedback (particularly on violations of the versioning policy).