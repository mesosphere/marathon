---
title: Common Problems
---

# Environment-Specific Tweaks

This sections contains some adjustments that you need to make when running Marathon in specific environments,
e.g. particular cloud environments. You are invited to contribute your tips to our documentation!

## Deploying Marathon on CoreOS (Docker)

By default, CoreOS does not populate `/etc/hosts` which may lead to Marathon crashing on startup.
You may see something like this in Marathon logs (or syslog)
`F0728 00:56:32.080438 1571 process.cpp:889] Name or service not known`

The easy way to fix is to allow CoreOS to manage `/etc/hosts` with the `manage_etc_hosts: localhost` attribute in your cloud config. Refer to the [CoreOS Cloud-Config documentation](https://coreos.com/os/docs/latest/cloud-config.html) for more details.

Check lookups work by running `hostname -f` and verify it returns successfully (exit code 0)

## Running Marathon on Azure Linux

Similar to CoreOS, sometimes Azure Linux VMs (observed mostly on Ubuntu) are missing a hostname entry in `/etc/hosts`. If you find running sudo commands takes a long time or `hostname -f` exits with an error, modify your `/etc/hosts` file and prepend it with an entry for your current hostname. E.g. '127.0.0.1   {my-host-name}'

Once `hostname -f` returns successfully, Marathon should be able to start.

# Task Timeout using Docker Containerizer

The default executor timeout on mesos slaves or Marathon's `task_launch_timeout` are too low for Docker containerizers (docker pull can take some time...). Refer to the docs on [Docker Containers]({{ site.baseurl }}/docs/native-docker.html)

# Using HTTPS with a local CA certificate

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

# Error reading authentication secret from file

When using framework-authentication on the master, be sure to set a secret that has a minimum of eight characters. Secrets shorter than that length may not be accepted by Marathon.
