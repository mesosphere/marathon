---
title: Error Using HTTPS with local CA Certificate
---

# Error Using HTTPS with local CA Certificate

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

