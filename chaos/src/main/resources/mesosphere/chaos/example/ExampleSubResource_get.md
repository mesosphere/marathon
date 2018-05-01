## GET /foo/bar

Prints system properties.

### Example

    $ http localhost:8080/foo/bar
    HTTP/1.1 200 OK
    Content-Type: application/json
    Server: Jetty(8.1.11.v20130520)
    Transfer-Encoding: chunked

    {
        "DEBUG": "",
        "awt.nativeDoubleBuffering": "true",
        "awt.toolkit": "apple.awt.CToolkit",
        "file.encoding": "UTF-8"
        // ...
    }
