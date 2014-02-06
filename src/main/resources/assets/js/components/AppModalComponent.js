/** @jsx React.DOM */

define([
  "React",
  "jsx!components/ModalComponent",
  "jsx!components/TabPaneComponent",
  "jsx!components/TaskListComponent",
  "jsx!components/TogglableTabsComponent",
  "mixins/BackboneMixin"
], function(React, ModalComponent, TabPaneComponent,
    TaskListComponent, TogglableTabsComponent, BackboneMixin) {

  return React.createClass({
    destroy: function() {
      this.refs.modalComponent.destroy();
    },
    destroyApp: function() {
      if (confirm("Destroy app '" + this.props.model.get("id") + "'?\nThis is irreversible.")) {
        this.props.model.destroy();
        this.refs.modalComponent.destroy();
      }
    },
    getResource: function() {
      return this.props.model;
    },
    getInitialState: function() {
      return {
        selectedTasks: {}
      };
    },
    killSelectedTasks: function(options) {
      var _this = this;
      var _options = options || {};

      var selectedTaskIds = Object.keys(this.state.selectedTasks);
      var tasksToKill = this.props.model.tasks.filter(function(task) {
        return selectedTaskIds.indexOf(task.id) >= 0;
      });

      tasksToKill.forEach(function(task) {
        task.destroy({
          scale: _options.scale,
          success: function() {
            var instances;
            if (_options.scale) {
              instances = _this.props.model.get("instances");
              _this.props.model.set("instances", instances - 1);
            }

            delete _this.state.selectedTasks[task.id];

            // Force an update since React doesn't know a key was removed from
            // `selectedTasks`.
            _this.forceUpdate();
          },
          wait: true
        });
      });
    },
    killSelectedTasksAndScale: function() {
      this.killSelectedTasks({scale: true});
    },
    mixins: [BackboneMixin],
    refreshTaskList: function() {
      this.refs.taskList.fetchTasks();
    },
    render: function() {
      var buttons;
      var model = this.props.model;
      var selectedTasksLength = Object.keys(this.state.selectedTasks).length;

      if (selectedTasksLength === 0) {
        buttons =
          <p>
            <button className="btn btn-sm btn-default" onClick={this.refreshTaskList}>
              ↻ Refresh
            </button>
          </p>;
      } else {
        // Killing two tasks in quick succession raises an exception. Disable
        // "Kill & Scale" if more than one task is selected to prevent the
        // exception from happening.
        //
        // TODO(ssorallen): Remove once
        //   https://github.com/mesosphere/marathon/issues/108 is addressed.
        buttons =
          <p class="btn-group">
            <button className="btn btn-sm btn-default" onClick={this.killSelectedTasks}>
              Kill
            </button>
            <button className="btn btn-sm btn-default" disabled={selectedTasksLength > 1}
                onClick={this.killSelectedTasksAndScale}>
              Kill &amp; Scale
            </button>
          </p>;
      }

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
          return <dd>{c.filter(function(s) { return s !== ""; }).join(":")}</dd>;
        });
      var containerNode = (model.get("container") == null) ?
        <dd className="text-muted">Unspecified</dd> :
        <dd>{JSON.stringify(model.get("container"))}</dd>;
      var envNode = (Object.keys(model.get("env")).length === 0) ?
        <dd className="text-muted">Unspecified</dd> :

        // Print environment variables as key value pairs like "key=value"
        Object.keys(model.get("env")).map(function(k) {
          return <dd>{k + "=" + model.get("env")[k]}</dd>
        });
      var executorNode = (model.get("executor") === "") ?
        <dd className="text-muted">Unspecified</dd> :
        <dd>{model.get("executor")}</dd>;
      var portsNode = (model.get("ports").length === 0 ) ?
        <dd className="text-muted">Unspecified</dd> :
        <dd>{model.get("ports").join(",")}</dd>;
      var urisNode = (model.get("uris").length === 0) ?
        <dd className="text-muted">Unspecified</dd> :
        model.get("uris").map(function(u) {
          return <dd>{u}</dd>;
        });

      return (
        <ModalComponent ref="modalComponent" onDestroy={this.props.onDestroy} size="lg">
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
              {buttons}
              <TaskListComponent collection={model.tasks}
                ref="taskList" selectedTasks={this.state.selectedTasks}
                onAllTasksToggle={this.toggleAllTasks}
                onTaskToggle={this.toggleTask} />
            </TabPaneComponent>
            <TabPaneComponent id="configuration">
              <dl className="dl-horizontal">
                <dt>Command</dt>
                {cmdNode}
                <dt>Constraints</dt>
                {constraintsNode}
                <dt>Container</dt>
                {containerNode}
                <dt>Environment</dt>
                {envNode}
                <dt>Executor</dt>
                {executorNode}
                <dt>Ports</dt>
                {portsNode}
                <dt>URIs</dt>
                {urisNode}
              </dl>
            </TabPaneComponent>
          </TogglableTabsComponent>
          <div className="modal-footer">
            <button className="btn btn-sm btn-danger" onClick={this.destroyApp}>
              Destroy
            </button>
            <button className="btn btn-sm btn-default"
                onClick={this.suspendApp} disabled={this.props.model.get("instances") < 1}>
              Suspend
            </button>
            <button className="btn btn-sm btn-default" onClick={this.scaleApp}>
              Scale
            </button>
          </div>
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
        model.set({instances: instances}, {validate: true});
        if (model.validationError == null) {
          model.save();
        } else {
          alert("Not scaling: " + model.validationError[0]);
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
        this.props.model.save({instances: 0});
      }
    }
  });
});
