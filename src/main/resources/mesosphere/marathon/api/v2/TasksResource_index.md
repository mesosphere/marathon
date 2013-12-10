## GET /v2/tasks

List tasks of all running applications.

### Example

TODO: revise JSON format

    $ http localhost:8080/v2/tasks
    HTTP/1.1 200 OK
    Content-Type: application/json
    Server: Jetty(8.1.11.v20130520)
    Transfer-Encoding: chunked

    {
        "sleep": [
            {
                "host": "localhost",
                "id": "sleep_1-1386657786214",
                "ports": [
                    31066
                ]
            },
            {
                "host": "localhost",
                "id": "sleep_0-1386657780866",
                "ports": [
                    31560
                ]
            }
        ]
    }
