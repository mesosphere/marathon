---
title: SSL and Basic Access Authentication
---

# SSL and Basic Access Authentication

Marathon enables you to secure its API endpoints via SSL and limit access to them
with HTTP basic access authentication. If you plan to use basic authentication,
we suggest enabling SSL as well. Otherwise the username and password will be
transmitted unencrypted and easily read by unintended parties.

## Enabling SSL

If you already have a Java keystore, pass it to Marathon along with the
keystore's password:

```sh
$ cd /path/to/marathon
$ ./bin/start --master zk://localhost:2181/mesos \
                  --zk zk://localhost:2181/marathon \
       --ssl_keystore_path marathon.jks \
   --ssl_keystore_password $MARATHON_JKS_PASSWORD
```

By default, Marathon serves SSL requests on port 8443 (this port can be changed with
the `--https_port`
[command line flag]({{ site.baseurl }}/docs/command-line-flags.html)). Once
  Marathon is running, access its API and UI via its HTTPS port:

```sh
$ curl https://localhost:8443/v2/apps
```

### Generating a keystore with an SSL key and certificate

If you do not already have a Java keystore, follow the steps below to create one.

<div class="alert alert-warning">
  <strong>Careful:</strong> Modern browsers and most tools will give users a
  warning and make it difficult to access the Marathon API and UI unless the SSL
  certificate in your keystore is signed by a trusted certificate authority.
  Either purchase an SSL certificate from a trusted authority or distribute your
  company's root certificate to users of the Marathon API and UI.
</div>

1. Generate an RSA private key with
   [OpenSSL](https://www.openssl.org/docs/apps/genrsa.html).

    ```sh
    # Set key password to env variable `MARATHON_KEY_PASSWORD`
    $ openssl genrsa -des3 -out marathon.key -passout "env:MARATHON_KEY_PASSWORD"
    ```

2. Acquire a certificate for the key by one of the following methods:
  * **(Recommended)** Purchase a certificate from a trusted certificate
    authority. This ensures that users of the API and UI of your Marathon instances will
    already trust the SSL certificate, preventing extra steps for them.
  * (Untrusted) Generate a certificate for the key. The following command prompts for
    information to secure the keystore. The "Common Name" must be the
    fully-qualified hostname of where you intend to use the certificate.

        ```sh
        # Read key password from env env variable `MARATHON_KEY_PASSWORD`
        $ openssl req -new -x509 -key marathon.key \
                              -passin "env:MARATHON_KEY_PASSWORD" \
                                 -out self-signed-marathon.pem
        ```

3. Combine the key and certificate files into a PKCS12 format file, the format
   used by the Java keystore. If the certificate you received is not in the
   `.pem` format, see the
   [Jetty SSL configuration](http://www.eclipse.org/jetty/documentation/current/configuring-ssl.html#loading-keys-and-certificates)
   docs to learn how to convert it.

    ```sh
    # Read key password from env variable `MARATHON_KEY_PASSWORD`
    # Set PKCS password to env variable `MARATHON_PKCS_PASSWORD`
    $ openssl pkcs12 -inkey marathon.key \
                    -passin "env:MARATHON_KEY_PASSWORD" \
                      -name marathon \
                        -in trusted.pem \
                  -password "env:MARATHON_PKCS_PASSWORD" \
             -chain -CAfile "trustedCA.crt" \
               -export -out marathon.pkcs12
    ```

4. Import the keystore.

    ```sh
    # Read PKCS password from env variable `MARATHON_PKCS_PASSWORD`
    # Set JKS password to env variable `MARATHON_JKS_PASSWORD`
    $ keytool -importkeystore -srckeystore marathon.pkcs12 \
                                 -srcalias marathon \
                             -srcstorepass $MARATHON_PKCS_PASSWORD \
                             -srcstoretype PKCS12 \
                             -destkeystore marathon.jks \
                            -deststorepass $MARATHON_JKS_PASSWORD
    ```

5. Start Marathon with the keystore and the password you chose when creating the
   keystore.

    ```sh
    # Read JKS password from env variable `MARATHON_JKS_PASSWORD`
    $ ./bin/start --master zk://localhost:2181/mesos \
                      --zk zk://localhost:2181/marathon \
           --ssl_keystore_path marathon.jks \
       --ssl_keystore_password $MARATHON_JKS_PASSWORD
    ```

6. Access the Marathon API and UI via its HTTPS port (default 8443).

    [https://\<MARATHON_HOST\>:8443](https://<MARATHON_HOST>:8443)

## Enabling Basic Access Authentication

<div class="alert alert-info">
  <strong>Note:</strong> It's highly recommended to enable SSL if you
  plan to use basic authentication. If SSL is not enabled, the username and
  password for your Marathon instances will be transmitted unencrypted and can
  easily be read by unintended parties.
</div>

Enable basic authentication by passing the username and password separated by a colon (:) to the
`--http_credentials` command line flag. *Note:
The username cannot contain a colon.*

```sh
$ cd /path/to/marathon
$ ./bin/start --master zk://localhost:2181/mesos \
                  --zk zk://localhost:2181/marathon \
        --http_credentials "cptPicard:topSecretPa$$word" \
       --ssl_keystore_path /path/to/marathon.jks \
   --ssl_keystore_password $MARATHON_JKS_PASSWORD
```
