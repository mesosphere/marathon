---
title: SSL and Basic Access Authentication
---

# SSL and Basic Access Authentication

Marathon enables securing its API endpoints via SSL and limiting access to them
with HTTP basic access authentication. If you plan to use basic authentication,
we suggest enabling SSL as well otherwise the username and password will be
transmitted unencrypted and easily read by unintended parties.

## Enabling SSL

If you already have a Java keystore, pass it to Marathon along with the
keystore's password to enable SSL:

```sh
$ cd /path/to/marathon
$ ./bin/start --master zk://localhost:2181/mesos \
                  --zk zk://localhost:2181/marathon \
       --ssl_keystore_path marathon-keystore \
   --ssl_keystore_password ********
```

By default, Marathon serves SSL requests on port 8443 (that can be changed with
the `--https_port`
[command line flag]({{ site.baseurl }}/docs/command-line-flags.html)). Once
  Marathon is running, access its API and UI via its HTTPS port:

```sh
$ curl https://localhost:8443/v2/apps
```

### Generating a keystore with an SSL key and certificate

<div class="alert alert-warning">
  <strong>Careful:</strong> Modern browsers and most tools will give users a
  warning and make it difficult to access the Marathon API and UI unless the SSL
  certificate in your keystore is signed by a trusted certificate authority.
  Either purchase an SSL certificate from a trusted authority or distribute your
  company's root certificate to users of the Marathon API and UI.
</div>

1. Generate a key with OpenSSL.

    ```sh
    $ openssl genrsa -des3 -out marathon.key
    ```

2. Acquire a certificate for the key by one of the following methods:
  * **(Recommended)** Purchase a certificate from a trusted certificate
    authority. This ensures users of your Marathon instances' API and UI will
    already trust the SSL certificate, preventing extra steps for your users.
  * (Untrusted) Generate a certificate for the key. This command prompts for a
    information to secure the keystore. The "Common Name" must be the
    fully-qualified hostname of where you intend to use the certificate.

        ```sh
        $ openssl req -new -x509 -key marathon.key -out self-signed-marathon.crt
        ```

3. Combine the key and certificate files into a PKCS12 format file, the file
   format used by the Java keystore.

    If the certificate you received is not in the `.crt` format, see the
    [Jetty SSL configuration](http://www.eclipse.org/jetty/documentation/current/configuring-ssl.html#loading-keys-and-certificates)
    documentation for details on how to convert it.

    _A non-empty password is required for the "export password" in order for the
    Java keytool to use the generated file._

    ```sh
    $ openssl pkcs12 -inkey marathon.key \
                        -in trusted.crt \
               -export -out marathon.pkcs12
    ```

4. Import the keystore. Note the password you use to generate the keystore.
   You will need to pass it to Marathon when starting each instance.

    ```sh
    $ keytool -importkeystore -srckeystore marathon.pkcs12 \
                             -srcstoretype PKCS12 \
                             -destkeystore marathon-keystore
    ```

5. Start Marathon with the keystore and the password you chose when creating the
   keystore.

    ```sh
    $ cd /path/to/marathon
    $ ./bin/start --master zk://localhost:2181/mesos \
                      --zk zk://localhost:2181/marathon \
           --ssl_keystore_path marathon-keystore \
       --ssl_keystore_password ******** # Password from step 4
    ```

## Enabling Basic Access Authentication

<div class="alert alert-info">
  <strong>Note:</strong> It's highly recommended to enable SSL as well if you
  plan to use basic authentication. If SSL is not enabled, the username and
  password for your Marathon instances will be transmitted unecrypted and can
  easily be read by unintended parties.
</div>

Pass the username and password separated by a colon (:) to the
`--http_credentials` command line flag to enable basic authentication. *Note:
The username cannot contain a colon.*

```sh
$ cd /path/to/marathon
$ ./bin/start --master zk://localhost:2181/mesos \
                  --zk zk://localhost:2181/marathon \
        --http_credentials "cptPicard:topSecretPa$$word" \
       --ssl_keystore_path /path/to/keystore \
   --ssl_keystore_password ********
```
