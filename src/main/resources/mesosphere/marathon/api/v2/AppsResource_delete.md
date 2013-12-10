## DELETE /v2/apps/{id}

Destroy an application. All data about that application will be deleted.

### Example

    $ http DELETE localhost:8080/v2/apps/sleep
    HTTP/1.1 204 No Content
    Content-Type: application/json
    Server: Jetty(8.1.11.v20130520)
