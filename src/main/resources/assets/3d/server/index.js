var _ = require("underscore");
var express = require("express");
var nconf = require("nconf");


nconf.argv().env().defaults({
  apps: 5,
  tasks: 1000
});

var app = express();

app.use(function (req, res, next) {
  res.header("Access-Control-Allow-Origin", "*");
  res.header("Access-Control-Allow-Headers", "Origin, X-Requested-With, Content-Type, Accept");
  next();
});

app.get("/v2/apps", function (req, res) {
  var apps = _.times(nconf.get("apps"), function (i) {
    return {
      id: "/app" + i,
      tasks: nconf.get("tasks"),
      tasksRunning: 0,
      tasksHealthy: 0
    };
  });
  res.json({ apps: apps });
});

app.get("/v2/apps/:appId/tasks", function (req, res) {
  var tasks = _.times(nconf.get("tasks"), function (i) {
    return {
      appId: "/" + req.params.appId,
      id: req.params.appId + "_instance" + i,
      host: "foo.bar.example.com"
    };
  });
  res.json({ tasks: tasks });
});

var server = app.listen(3000, function () {
  var host = server.address().address;
  var port = server.address().port;
  console.log("Server listening at http://%s:%s", host, port);
});

