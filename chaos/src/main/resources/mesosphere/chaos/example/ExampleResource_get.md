## GET /foo

Returns a person object as JSON.

### Example

    $ curl -i localhost:8080/foo
    HTTP/1.1 200 OK
    Content-Type: application/json
    Transfer-Encoding: chunked
    Server: Jetty(8.1.11.v20130520)

    {"name":"Walter","age":128}
