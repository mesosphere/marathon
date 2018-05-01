## POST /foo

Post a person object as JSON. It will be written to the server's STDOUT.

### Example

    $ curl -i --data-binary '{"name":"Walter","age":128}' -H 'Content-Type: application/json' localhost:8080/foo
    HTTP/1.1 204 No Content
    Content-Type: application/json
    Server: Jetty(8.1.11.v20130520)
