## DELETE `/v2/deployments/{deployment_id}

Will revert the deployment of the deployment identified by given identifier.
The state before the deployment took place will be restored.
The cancellation of a deployment will create a new deployment and replaces the old one.


### Example

**Request:**

```
DELETE /v2/deployments/2e72dbf1-2b2a-4204-b628-e8bd160945dd HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Length: 0
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2
```

**Response:**

```
HTTP/1.1 200 OK
Content-Type: application/json
Server: Jetty(8.1.11.v20130520)
Transfer-Encoding: chunked
{
    "deploymentId": "52c51d0a-27eb-4971-a0bb-b0fa47528e33", 
    "version": "2014-07-09T11:14:58.232Z"
}
```
