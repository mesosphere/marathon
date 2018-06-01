# Deprecation Mechanism

## Overview

Marathon has a standardized mechanism by which features are gracefully phased out, and strives to realize the following ideals:

1. Operators can clearly tell if they are using a deprecated feature that will soon be completely unavailable.
2. Operators can safely test that their integrations continue to work before upgrading to a Marathon version that
   doesn't include behavior that integrations potentially rely on.
3. Deprecation phases are clearly defined and consistently enforced.

## Hypothetical Deprecation Example

The Deprecation Mechanism is best described by a hypothetical example involving a a plan to remove `/v2/oldroute` from
our API. The deprecation will be advertised in our release notes, along with the release deprecation schedule.

Hypothetical release notes:

> # 1.5.9 (hypothetical release)
>
> ## Deprecated Changes
>
> ### /v2/oldroute
>
> `/v2/oldroute` has been marked as deprecated and clients should be updated to use `/newroute`. `/v2/route` has the
> following deprecation schedule:
>
> - 1.5.x - `/v2/oldroute` will continue to function as normal.
> - 1.6.x - The API will stop responding to `/v2/oldroute`; requests to it will be met with a 404 response. The route
>   can be re-enabled by the command-line argument `--deprecated_features=api_oldroute`.
> - 1.7.x - `/v2/oldroute` is scheduled to be completely removed. If the `--deprecated_features=api_oldroute` is still
>   specified, Marathon will refuse to launch, with an error

As mentioned in the schedule, if Marathon is upgraded to `1.7.x` and `--deprecated_features=api_oldroute` is still
specified, then Marathon will refuse to launch. The expectation is for users to remove their dependence on deprecated
features _before_ they are removed. No state will be migrated if this flag is still specified, making it possible for
the operator to roll-back to the version of Marathon prior to the upgrade, remove the
`--deprecated_features=api_oldroute` flag, confirm that they no longer depend on it, and then migrate again.
