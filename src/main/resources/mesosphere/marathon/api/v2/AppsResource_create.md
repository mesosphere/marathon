## POST /v2/apps

Create and start a new application.

### Example

    $ http POST localhost:8080/v2/apps id=sleep cmd='sleep 60' \
      instances=1 cpus=0.1 mem=10
    HTTP/1.1 204 No Content
    Content-Type: application/json
    Server: Jetty(8.1.11.v20130520)
