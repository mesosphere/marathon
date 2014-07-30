## DELETE `/v2/apps/{app_id}`

Destroy an application. All data about that application will be deleted.

### Example

**Request:**

```
DELETE /v2/apps/myapp HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Length: 0
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2


```


**Response:**

```
HTTP/1.1 204 No Content
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)


```
