## PUT /v2/apps/{id}

Change parameters of a running application. Currently only the number of 
instances can be changed.

### Example

    $ http PUT localhost:8080/v2/apps/sleep instances=2
    HTTP/1.1 204 No Content
    Content-Type: application/json
    Server: Jetty(8.1.11.v20130520)
