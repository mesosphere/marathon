---
title: Troubleshooting
---

# Troubleshooting

## An app stays in "Waiting" forever

This means that Marathon does not receive "Resource Offers" from Mesos that allow it to start tasks of
this application. The simplest failure is that there are not sufficient resources available in the cluster or another
framework hords all these resources. You can check the Mesos UI for available resources. Note that the required resources
(such as CPU, Mem, Disk) have to be all available on a single host.

If you do not find the solution yourself and you create a GitHub issue, please append the output of Mesos `/state` endpoint to the bug report so that we can inspect available cluster resources.

### Requiring a specific host port

Generally, it is not recommended to configure your apps to depend on a particular host port because it constraints
scheduling. We know that it is sometimes only avoidable at great cost so Marathon allows you to specify a required
host port by:

* ([For Docker Apps with Bridge Networking]({{ site.baseurl }}/docs/native-docker.html#bridged-networking-mode)):
  Specifying a non-zero `"hostPort"` in your `"portBindings"`.
* Setting setting `"requirePorts": true` in your app definition.

Marathon can only use offers for such offers that include the required host ports. By default, Mesos agents do NOT offer
all ports, so if you require port e.g. 80 you have to make sure that this port is offered by the agent. You can make all
ports from 1 to 65000 available to the mesos agent by using `--resources=ports:[1-65000]`. Please make sure that only
tasks scheduled by Mesos use ports in the specified range! You can find the relevant configuration options for the Mesos
agent [here](http://mesos.apache.org/documentation/attributes-resources/).

## Using HTTPS with a local CA certificate

You might get errors like the following in the Marathon log if you are running Marathon with only HTTPS enabled
(`--ssl_keystore_password $MARATHON_JKS_PASSWORD --ssl_keystore_path /my/path/to/marathon.jks --https_port 8080 --disable_http`)
and your certificate is not signed by a public CA:

```
WARN /v2/deployments (org.eclipse.jetty.servlet.ServletHandler:563)
java.lang.RuntimeException: while proxying
  at mesosphere.marathon.api.LeaderProxyFilter.doFilter(LeaderProxyFilter.scala:147)
  [...]
Caused by: javax.net.ssl.SSLHandshakeException: sun.security.validator.ValidatorException: PKIX path building failed: sun.security.provider.certpath.SunCertPathBuilderException: unable to find valid certification path to requested target
  [...]
Caused by: sun.security.validator.ValidatorException: PKIX path building failed: sun.security.provider.certpath.SunCertPathBuilderException: unable to find valid certification path to requested target
  [...]
```

In this case, make sure that your local CA is included in your keystore by executing something like:

```
keytool -import -trustcacerts -file ./cacert.cer -alias yourca --keystore /my/path/to/marathon.jks
```

## Error reading authentication secret from file

When using framework-authentication on the master, be sure to set a secret that has a minimum of eight characters. Secrets shorter than that length may not be accepted by Marathon.
