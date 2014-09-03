/** @jsx React.DOM */

define([
  "Underscore",
  "React",
  "mixins/BackboneMixin",
  "models/AppVersionCollection",
  "jsx!components/AppVersionListComponent",
  "jsx!components/ModalComponent",
  "jsx!components/StackedViewComponent",
  "jsx!components/TabPaneComponent",
  "jsx!components/TaskDetailComponent",
  "jsx!components/TaskViewComponent",
  "jsx!components/TogglableTabsComponent"
], function(_, React, BackboneMixin, AppVersionCollection,
    AppVersionListComponent, ModalComponent, StackedViewComponent,
    TabPaneComponent, TaskDetailComponent, TaskViewComponent,
    TogglableTabsComponent) {
  "use strict";

  var STATES = {
    STATE_LOADING: 0,
    STATE_ERROR: 1,
    STATE_SUCCESS: 2
  };
  var UPDATE_INTERVAL = 2000;

  return React.createClass({
    displayName: "AppModalComponent",

    mixins: [BackboneMixin],

    componentWillMount: function() {
      this.fetchTasks();
      var appVersions = new AppVersionCollection(null, {appId: this.props.model.id});
      this.setState({appVersions: appVersions});
    },

    componentDidMount: function() {
      this.startPolling();
    },

    componentWillUnmount: function() {
      this.stopPolling();
    },

    fetchAppVersions: function() {
      this.state.appVersions.fetch({
        error: function() {
          this.setState({appVersionsFetchState: STATES.STATE_ERROR});
        }.bind(this),
        success: function() {
          this.setState({appVersionsFetchState: STATES.STATE_SUCCESS});
        }.bind(this)
      });
    },

    getResource: function() {
      return this.props.model;
    },

    getInitialState: function() {
      return {
        activeTask: null,
        activeViewIndex: 0,
        appVersions: null,
        tasksFetchState: STATES.STATE_LOADING,
        appVersionsFetchState: STATES.STATE_LOADING
      };
    },

    fetchTasks: function() {
      this.props.model.tasks.fetch({
        error: function() {
          this.setState({tasksFetchState: STATES.STATE_ERROR});
        }.bind(this),
        success: function(collection, response) {
          // update changed attributes in app
          this.props.model.update(response.app);
          this.setState({tasksFetchState: STATES.STATE_SUCCESS});
        }.bind(this)
      });
    },

    destroy: function() {
      this.refs.modalComponent.destroy();
    },

    destroyApp: function() {
      if (confirm("Destroy app '" + this.props.model.get("id") + "'?\nThis is irreversible.")) {
        // Send force option to ensure the UI is always able to kill apps
        // regardless of deployment state.
        this.props.model.destroy({
          url: _.result(this.props.model, "url") + "?force=true"
        });

        this.refs.modalComponent.destroy();
      }
    },

    onTasksKilled: function(options) {
      var _options = options || {};
      if (_options.scale) {
        // refresh app versions
        this.fetchAppVersions();
      }
    },

    refreshTaskList: function() {
      this.refs.taskList.fetchTasks();
    },

    rollbackToAppVersion: function(appVersion) {
      this.props.model.setAppVersion(appVersion);
      this.props.model.save(
        null,
        {
          error: function() {
            this.setState({appVersionsFetchState: STATES.STATE_ERROR});
          }.bind(this),
          success: function() {
            // refresh app versions
            this.fetchAppVersions();
          }.bind(this)
        });
    },

    render: function() {
      var model = this.props.model;

      var isDeploying = model.isDeploying();

      var statusClassSet = React.addons.classSet({
        "text-warning": isDeploying
      });

      var hasHealth = model.get("healthChecks") != null &&
        model.get("healthChecks").length > 0;

      /* jshint trailing:false, quotmark:false, newcap:false */
      return (
        <ModalComponent ref="modalComponent" onDestroy={this.props.onDestroy}
          size="lg">
          <div className="modal-header">
             <button type="button" className="close"
                aria-hidden="true" onClick={this.destroy}>&times;</button>
            <span className="h3 modal-title">{model.get("id")}</span>
            <ul className="list-inline list-inline-subtext">
              <li>
                  <span className={statusClassSet}>
                    {isDeploying ? "Deploying" : "Running" }
                  </span>
              </li>
            </ul>
            <div className="header-btn">
              <button className="btn btn-sm btn-default"
                  onClick={this.suspendApp}
                  disabled={this.props.model.get("instances") < 1}>
                Suspend
              </button>
              <button className="btn btn-sm btn-default" onClick={this.scaleApp}>
                Scale
              </button>
              <button className="btn btn-sm btn-danger pull-right" onClick={this.destroyApp}>
                Destroy App
              </button>
            </div>
          </div>
          <TogglableTabsComponent className="modal-body modal-body-no-top"
              tabs={[
                {id: "tasks", text: "Tasks"},
                {id: "configuration", text: "Configuration"}
              ]}>
            <TabPaneComponent id="tasks">
              <StackedViewComponent
                activeViewIndex={this.state.activeViewIndex}>
                <TaskViewComponent
                  collection={model.tasks}
                  fetchState={this.state.tasksFetchState}
                  fetchTasks={this.fetchTasks}
                  formatTaskHealthMessage={model.formatTaskHealthMessage}
                  hasHealth={hasHealth}
                  onTasksKilled={this.onTasksKilled}
                  onTaskDetailSelect={this.showTaskDetails}
                  STATES={STATES} />
                <TaskDetailComponent
                  fetchState={this.state.tasksFetchState}
                  taskHealthMessage={model.formatTaskHealthMessage(this.state.activeTask)}
                  hasHealth={hasHealth}
                  STATES={STATES}
                  onShowTaskList={this.showTaskList}
                  task={this.state.activeTask} />
              </StackedViewComponent>
            </TabPaneComponent>
            <TabPaneComponent
              id="configuration"
              onActivate={this.fetchAppVersions} >
              <AppVersionListComponent
                app={model}
                appVersions={this.state.appVersions}
                fetchAppVersions={this.fetchAppVersions}
                fetchState={this.state.appVersionsFetchState}
                onRollback={this.rollbackToAppVersion}
                STATES={STATES} />
            </TabPaneComponent>
          </TogglableTabsComponent>
        </ModalComponent>
      );
    },

    scaleApp: function() {
      var model = this.props.model;
      var instancesString = prompt("Scale to how many instances?",
        model.get("instances"));

      // Clicking "Cancel" in a prompt returns either null or an empty String.
      // perform the action only if a value is submitted.
      if (instancesString != null && instancesString !== "") {
        var instances = parseInt(instancesString, 10);
        model.save(
          {instances: instances},
          {
            error: function(data, response) {
              model.set(model.previousAttributes());
              alert("Not scaling: " + response.statusText);
            }.bind(this),
            success: function() {
              // refresh app versions
              this.fetchAppVersions();
            }.bind(this)
          }
        );

        if (model.validationError != null) {
          // If the model is not valid, revert the changes to prevent the UI
          // from showing an invalid state.
          model.set(model.previousAttributes());
          alert("Not scaling: " + model.validationError[0].message);
        }
      }
    },

    toggleAllTasks: function() {
      var newSelectedTasks = {};
      var modelTasks = this.props.model.tasks;

      // Note: not an **exact** check for all tasks being selected but a good
      // enough proxy.
      var allTasksSelected = Object.keys(this.state.selectedTasks).length ===
        modelTasks.length;

      if (!allTasksSelected) {
        modelTasks.forEach(function(task) { newSelectedTasks[task.id] = true; });
      }

      this.setState({selectedTasks: newSelectedTasks});
    },

    toggleTask: function(task, value) {
      var selectedTasks = this.state.selectedTasks;

      // If `toggleTask` is used as a callback for an event handler, the second
      // parameter will be an event object. Use it to set the value only if it
      // is a Boolean.
      var localValue = (typeof value === Boolean) ?
        value :
        !selectedTasks[task.id];

      if (localValue === true) {
        selectedTasks[task.id] = true;
      } else {
        delete selectedTasks[task.id];
      }

      this.setState({selectedTasks: selectedTasks});
    },

    suspendApp: function() {
      if (confirm("Suspend app by scaling to 0 instances?")) {
        this.props.model.suspend({
          error: function() {
            this.setState({appVersionsFetchState: STATES.STATE_ERROR});
          }.bind(this),
          success: function() {
            // refresh app versions
            this.fetchAppVersions();
          }.bind(this)
        });
      }
    },

    showTaskDetails: function(task) {
      this.setState({
        activeTask: task,
        activeViewIndex: 1
      });
    },

    showTaskList: function() {
      this.setState({
        activeTask: null,
        activeViewIndex: 0
      });
    },

    startPolling: function() {
      if (this._interval == null) {
        this._interval = setInterval(this.fetchTasks, UPDATE_INTERVAL);
      }
    },

    stopPolling: function() {
      clearInterval(this._interval);
      this._interval = null;
    }
  });
});
