## DELETE `/v2/groups/{group_id}`

Destroy a group. All data about that group and all associated applications will be deleted.

### Example

**Request:**

```
DELETE /v2/groups/myProduct HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Length: 0
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: curl/7.35.0
```


**Response:**

```
HTTP/1.1 200 OK
Server: Jetty(8.y.z-SNAPSHOT)
```
