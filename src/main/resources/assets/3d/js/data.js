var Marathon = (function () {

  /////////////////////////////////////////////////////////
  // EDIT THE BELOW URL TO POINT TO YOUR MARATHON API    //
  /////////////////////////////////////////////////////////
  var apiURL = "../v2";

  /*global Qajax, Lazy*/
  var callbacks = {
    created: [],
    updated: [],
    deleted: []
  };

  var nextGeneration = 0;

  var Tasks = {};

  var Events = {
    created: function (cb) {
      callbacks.created.push(cb);
    },
    updated: function (cb) {
      callbacks.updated.push(cb);
    },
    deleted: function (cb) {
      callbacks.deleted.push(cb);
    }
  };

  function fire (event, task) {
    callbacks[event].forEach(function (fn) {
      fn(task);
    });
  }

  function isTaskEqual (task1, task2) {
    return task1.id === task2.id
      && task1.appId === task2.appId
      && task1.running === task2.running
      && task1.healthy === task2.healthy;
  }

  function fetchApps (generation) {
    Qajax({
      url: apiURL + "/apps",
      headers: {"Accept": "application/json"}
      })
      .then(function (xhr) {
        var data = JSON.parse(xhr.responseText);
        data.apps.forEach(function (appData) {
          var app = {
            id: appData.id,
            instances: appData.instances,
            tasksRunning: appData.tasksRunning,
            tasksHealthy: appData.tasksHealthy
          };
          fetchTasks(app, generation);
        });
      });
  }

  function fetchTasks (app, generation) {
    Qajax({
      url: apiURL + "/apps" + app.id + "/tasks",
      headers: {"Accept": "application/json"}
      })
      .then(function (xhr) {
        var data = JSON.parse(xhr.responseText);
        data.tasks.forEach(function (taskData) {
          taskData.generation = generation;
          if (!Tasks.hasOwnProperty(taskData.id)) {
            fire("created", taskData);
          } else if (!isTaskEqual(Tasks[taskData.id], taskData)) {
            fire("updated", taskData);
          }
          Tasks[taskData.id] = taskData;
        });

        Tasks.forEach(function (task) {
          if (task.appId === app.id && task.generation < generation) {
            fire("deleted", task);
            delete Tasks[task.id];
          }
        });
      });
  }

  function startPolling() {
    fetchApps(nextGeneration);
    setInterval(function () {
      nextGeneration++;
      fetchApps(nextGeneration);
    }, 2000);
  }

  return {
    Events: Events,
    startPolling: startPolling
  };
}());
