## GET /v2/apps/{appId}/tasks

List all running tasks for application `appId`.

### Example

    $ http localhost:8080/v2/apps/sleep/tasks
    HTTP/1.1 200 OK
    Content-Type: application/json
    Server: Jetty(8.1.11.v20130520)
    Transfer-Encoding: chunked

    {
        "sleep": [
            {
                "host": "localhost",
                "id": "sleep_0-1386662802034",
                "ports": [
                    31693
                ]
            },
            {
                "host": "localhost",
                "id": "sleep_1-1386662808020",
                "ports": [
                    31838
                ]
            }
        ]
    }
