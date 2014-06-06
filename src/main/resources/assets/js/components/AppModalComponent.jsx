/** @jsx React.DOM */

define([
  "React",
  "mixins/BackboneMixin",
  "jsx!components/ModalComponent",
  "jsx!components/StackedViewComponent",
  "jsx!components/TabPaneComponent",
  "jsx!components/TaskDetailComponent",
  "jsx!components/TaskViewComponent",
  "jsx!components/TogglableTabsComponent"
], function(React, BackboneMixin, ModalComponent, StackedViewComponent, TabPaneComponent,
    TaskDetailComponent, TaskViewComponent, TogglableTabsComponent) {

  var STATES = {
      STATE_LOADING: 0,
      STATE_ERROR: 1,
      STATE_SUCCESS: 2
    };
  var UPDATE_INTERVAL = 2000;

  return React.createClass({
    displayName: "AppModalComponent",
    statics: {
      STATES: STATES
    },
    mixins:[BackboneMixin],
    componentWillMount: function() {
      this.fetchTasks();
    },
    componentDidMount: function() {
      this.startPolling();
    },
    componentWillUnmount: function() {
      this.stopPolling();
    },

    getInitialState: function() {
      return {
        activeTask: null,
        activeViewIndex: 0,
        fetchState: STATES.STATE_LOADING
      };
    },

    getResource: function () {
      return this.props.model;
    },
    fetchTasks: function() {
      var _this = this;

      this.props.model.tasks.fetch({
        error: function() {
          _this.setState({fetchState: STATES.STATE_ERROR});
        },
        success: function() {
          _this.setState({fetchState: STATES.STATE_SUCCESS});
        }
      });
    },

    destroy: function() {
      this.refs.modalComponent.destroy();
    },
    destroyApp: function() {
      if (confirm("Destroy app '" + this.props.model.get("id") + "'?\nThis is irreversible.")) {
        this.props.model.destroy();
        this.refs.modalComponent.destroy();
      }
    },
    onTasksKilled:  function(options) {
      var instances;
      var _options = options || {};
      if (_options.scale) {
        instances = this.props.model.get("instances");
        this.props.model.set("instances", instances - 1);
      }
      
      // Force an update since React doesn't know a key was removed from
      // `selectedTasks`.
      this.forceUpdate();
    },

    render: function() {
      var _this = this;
      var model = this.props.model;
      var cmdNode = (model.get("cmd") == null) ?
        <dd className="text-muted">Unspecified</dd> :
        <dd>{model.get("cmd")}</dd>;
      var constraintsNode = (model.get("constraints").length < 1) ?
        <dd className="text-muted">Unspecified</dd> :
        model.get("constraints").map(function(c) {

          // Only include constraint parts if they are not empty Strings. For
          // example, a hostname uniqueness constraint looks like:
          //
          //     ["hostname", "UNIQUE", ""]
          //
          // it should print "hostname:UNIQUE" instead of "hostname:UNIQUE:", no
          // trailing colon.
          return (
            <dd key={c}>
              {c.filter(function(s) { return s !== ""; }).join(":")}
            </dd>
          );
        });
      var containerNode = (model.get("container") == null) ?
        <dd className="text-muted">Unspecified</dd> :
        <dd>{JSON.stringify(model.get("container"))}</dd>;
      var envNode = (Object.keys(model.get("env")).length === 0) ?
        <dd className="text-muted">Unspecified</dd> :

        // Print environment variables as key value pairs like "key=value"
        Object.keys(model.get("env")).map(function(k) {
          return <dd key={k}>{k + "=" + model.get("env")[k]}</dd>
        });
      var executorNode = (model.get("executor") === "") ?
        <dd className="text-muted">Unspecified</dd> :
        <dd>{model.get("executor")}</dd>;
      var portsNode = (model.get("ports").length === 0 ) ?
        <dd className="text-muted">Unspecified</dd> :
        <dd>{model.get("ports").join(",")}</dd>;
      var versionNode = (model.get("version") == null) ?
        <dd className="text-muted">Unspecified</dd> :
        <dd>{model.get("version").toLocaleString()}</dd>;
      var urisNode = (model.get("uris").length === 0) ?
        <dd className="text-muted">Unspecified</dd> :
        model.get("uris").map(function(u) {
          return <dd key={u}>{u}</dd>;
        });

      var footer;
      if (this.state.activeViewIndex === 0) { // onlys show in TaskViewComponent
        footer =
          <div className="modal-footer">
            <button className="btn btn-sm btn-danger" onClick={this.destroyApp}>
              Destroy
            </button>
            <button className="btn btn-sm btn-default"
                onClick={this.suspendApp}
                disabled={this.props.model.get("instances") < 1}>
              Suspend
            </button>
            <button className="btn btn-sm btn-default" onClick={this.scaleApp}>
              Scale
            </button>
          </div>;
      }

      return (
        <ModalComponent ref="modalComponent" onDestroy={this.props.onDestroy}
          size="lg">
          <div className="modal-header">
             <button type="button" className="close"
                aria-hidden="true" onClick={this.destroy}>&times;</button>
            <h3 className="modal-title">{model.get("id")}</h3>
            <ul className="list-inline">
              <li>
                <span className="text-info">Instances </span>
                <span className="badge">{model.get("instances")}</span>
              </li>
              <li>
                <span className="text-info">CPUs </span>
                <span className="badge">{model.get("cpus")}</span>
              </li>
              <li>
                <span className="text-info">Memory </span>
                <span className="badge">{model.get("mem")} MB</span>
              </li>
            </ul>
          </div>
          <TogglableTabsComponent className="modal-body"
              tabs={[
                {id: "tasks", text: "Tasks"},
                {id: "configuration", text: "Configuration"}
              ]}>
            <TabPaneComponent id="tasks">
              <StackedViewComponent
                activeViewIndex={this.state.activeViewIndex}>
                <TaskViewComponent
                  collection={model.tasks}
                  fetchState={this.state.fetchState}
                  fetchTasks={this.fetchTasks}
                  onTasksKilled={this.onTasksKilled}
                  onTaskDetailSelect={this.showTaskDetails} />
                <TaskDetailComponent task={this.state.activeTask}
                  fetchState={this.state.fetchState}
                  formatTaskHealthMessage={model.formatTaskHealthMessage}
                  onShowTaskList={this.showTaskList} />
              </StackedViewComponent>
            </TabPaneComponent>
            <TabPaneComponent id="configuration">
              <dl className="dl-horizontal">
                <dt>Command</dt>
                {cmdNode}
                <dt>Constraints</dt>
                {constraintsNode}
                <dt>Container</dt>
                {containerNode}
                <dt>CPUs</dt>
                <dd>{model.get("cpus")}</dd>
                <dt>Environment</dt>
                {envNode}
                <dt>Executor</dt>
                {executorNode}
                <dt>Instances</dt>
                <dd>{model.get("instances")}</dd>
                <dt>Memory (MB)</dt>
                <dd>{model.get("mem")}</dd>
                <dt>Ports</dt>
                {portsNode}
                <dt>URIs</dt>
                {urisNode}
                <dt>Version</dt>
                {versionNode}
              </dl>
            </TabPaneComponent>
          </TogglableTabsComponent>
          {footer}
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
        model.save({instances: instances});

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
        this.props.model.suspend();
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
