## GET `/v2/queue`

List all the tasks queued up or waiting to be scheduled.  This is mainly used for troubleshooting and occurs when scaling changes are requested and the volume of scaling changes out paces the ability to schedule those tasks.  

### Example (as JSON)

**Request:**

```
GET /v2/queue HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2


```

**Response:**

```
HTTP/1.1 200 OK
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

{  
   "queue":[  
      {  
         "id":"tomcat",
         "cmd":"mv *.war apache-tomcat-*/webapps && cd apache-tomcat-* && sed \"s/8080/$PORT/g\" < ./conf/server.xml > ./conf/server-mesos.xml && ./bin/catalina.sh run -config ./conf/server-mesos.xml",
         "env":{  

         },
         "instances":3,
         "cpus":1.0,
         "mem":512.0,
         "disk":0.0,
         "executor":"",
         "constraints":[  

         ],
         "uris":[  
            "/home/vagrant/apache-tomcat-7.0.55.tar.gz",
            "/home/vagrant/Calendar.war"
         ],
         "ports":[  
            16401
         ],
         "taskRateLimit":1.0,
         "container":null,
         "healthChecks":[  

         ],
         "version":"2014-08-08T17:04:19.022Z"
      }
   ]
}

```

