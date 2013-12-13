## GET /v2/apps

List all running applications.

### Example

    $ http localhost:8080/v2/apps
    HTTP/1.1 200 OK
    Content-Type: application/json
    Server: Jetty(8.1.11.v20130520)
    Transfer-Encoding: chunked

    {
        "apps": [
            {
                "cmd": "sleep 60",
                "constraints": [],
                "cpus": 0.1,
                "env": {},
                "executor": "",
                "id": "sleep",
                "instances": 1,
                "mem": 10.0,
                "ports": [
                    16779
                ],
                "taskRateLimit": 1.0,
                "uris": []
            }
        ]
    }
