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
  var Apps = {};

  var appDefaults = {
    instances: 0,
    tasksStaging: 0,
    tasksRunning: 0,
    tasksHealthy: 0,
    tasksUnhealthy: 0,
    tasks: []
  };

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
          //fetchTasks(app, generation);
          simulateFetchTasks(app, generation);
        });
      });
  }

  function simulateFetchTasks (app, generation) {
    var oldApp = Apps[app.id] || appDefaults;
    var tasks = oldApp.tasks;

    deleteTasks(tasks, app, oldApp);
    createTasks(tasks, app, oldApp, generation);
    updateTasks(tasks, app, oldApp, generation);

    app.tasks = tasks;
    Apps[app.id] = app;
  }
  
  function createTasks (tasks, app, oldApp, generation) {
    for (var i = oldApp.instances; i < app.instances; i++) {
      var newTask = {
        id: app.id + "_" + i,
        appId: app.id,
        running: 1,
        healthy: 1,
        generation: generation
      };
      fire("created", newTask);
      tasks.push(newTask);
    }
  }

  function updateTasks (tasks, app, oldApp, generation) {
    var deltaHealthy = app.tasksHealthy - oldApp.tasksHealthy;
    var deltaUnhealthy = app.tasksUnhealthy - oldApp.tasksUnhealthy;

    for (var i = 0; i < tasks.length; i++) {
      var task = tasks[i];
      var taskRunning = Number(i <= app.tasksRunning);
      if (task.running !== taskRunning) {
        task.running = taskRunning;
        if (task.generation !== generation) {
          fire("updated", task);
        }
      }
      if (deltaHealthy > 0 && task.healthy === 0) {
        task.healthy = 1;
        deltaHealthy--;
        if (task.generation !== generation) {
          fire("updated", task);
        }
      } else if (deltaUnhealthy > 0 && task.healthy === 1) {
        task.healthy = 0;
        deltaUnhealthy--;
        if (task.generation !== generation) {
          fire("updated", task);
        }
      }
    }
  }

  function deleteTasks (tasks, app, oldApp) {
    for (var i = oldApp.instances; i > app.instances; i--) {
      var deadTask = tasks.pop();
      fire("deleted", deadTask);
    }
  }

  // The below performs a fetch the the /tasks/ endpoint and retrieves
  // individual task statuses. We have concerns about its performance 
  // under hyperscale load, so it is not currently used.
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
