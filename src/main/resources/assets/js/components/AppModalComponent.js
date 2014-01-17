/** @jsx React.DOM */

define([
  "React",
  "jsx!components/ModalComponent",
  "jsx!components/TaskListComponent",
  "mixins/BackboneMixin"
], function(React, ModalComponent, TaskListComponent, BackboneMixin) {
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

      return (
        <ModalComponent ref="modalComponent" onDestroy={this.props.onDestroy}>
          <div className="modal-header">
             <button type="button" className="close"
                aria-hidden="true" onClick={this.destroy}>&times;</button>
            <h3 className="modal-title">{model.get("id")}</h3>
          </div>
          <div className="modal-body">
            <dl className="dl-horizontal">
              <dt>CMD:</dt><dd>{model.get("cmd")}</dd>
              <dt>URIs:</dt><dd>{model.get("uris").length}</dd>
              <dt>Memory (MB):</dt><dd>{model.get("mem")}</dd>
              <dt>CPUs:</dt><dd>{model.get("cpus")}</dd>
              <dt>Instances:</dt><dd>{model.get("instances")}</dd>
            </dl>
            {buttons}
            <TaskListComponent collection={model.tasks}
              ref="taskList" selectedTasks={this.state.selectedTasks}
              onAllTasksToggle={this.toggleAllTasks}
              onTaskSelect={this.selectTask}
              onTaskToggle={this.toggleTask} />
          </div>
          <div className="modal-footer">
            <button className="btn btn-sm btn-danger" onClick={this.destroyApp}>
              DESTROY
            </button>
            <button className="btn btn-sm btn-default"
                onClick={this.suspendApp} disabled={this.props.model.get("instances") < 1}>
              SUSPEND
            </button>
            <button className="btn btn-sm btn-default" onClick={this.scaleApp}>
              SCALE
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
    selectTask: function(task, event) {
      this.toggleTask(task, event.target.checked);
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
