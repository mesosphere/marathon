## DELETE /v2/apps/{appId}/tasks/{taskId} 

Kill the task with ID `taskId` that belongs to the application `appId`.

### Example

    $ http DELETE localhost:8080/v2/apps/sleep/tasks/sleep_1-1386662863078
    HTTP/1.1 200 OK
    Content-Type: application/json
    Server: Jetty(8.1.11.v20130520)
    Transfer-Encoding: chunked

    [
        {
            "host": "localhost",
            "id": "sleep_1-1386662863078",
            "ports": [
                31782
            ]
        }
    ]
