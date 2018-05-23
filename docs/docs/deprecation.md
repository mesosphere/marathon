# Deprecation Mechanism

Marathon has a standardized mechanism by which features are gracefully phased out. It's behavior is best described by an example:

1. In 1.5.0, `/v2/oldroute` is marked as deprecated.
2. In 1.6.0, `/v2/oldroute` is disabled by default. However, the old deprecated behavior can be brought back for a little while longer with `--deprecated_features=oldroute`.
3. In 1.7.0, if `--deprecated_features=oldroute` is specified, Marathon will refuse to launch (and, consequently, refuse to migrate the Marathon schema so a downgrade is possible)

The ideals that this deprecation mechanism fulfill are:

1. Operators can tell if they are using a deprecated feature that will soon be completely unavailable
2. Operators can safely test that their integrations continue to work before upgrading to a Marathon version that doesn't include behavior that integrations potentially rely on.
3. Deprecation phases are clearly defined and consistently enforced.
