/** @jsx React.DOM */

define([
  "React",
  "jsx!components/TaskListComponent"
], function(React, TaskListComponent) {
  
  return React.createClass({
    displayName: "TaskViewComponent",
    getInitialState: function() {
      return {
        selectedTasks: {}
      };
    },
    getResource: function() {
      return this.props.collection;
    },
    killSelectedTasks: function(options) {
      var _this = this;
      var _options = options || {};

      var selectedTaskIds = Object.keys(this.state.selectedTasks);
      var tasksToKill = this.props.collection.filter(function(task) {
        return selectedTaskIds.indexOf(task.id) >= 0;
      });

      tasksToKill.forEach(function(task) {
        task.destroy({
          scale: _options.scale,
          success: function () {
            _this.props.onTasksKilled(_options);
            delete _this.state.selectedTasks[task.id];
          },
          wait: true
        });
      });
    },
    killSelectedTasksAndScale: function() {
      this.killSelectedTasks({scale: true});
    },
    toggleAllTasks: function() {
      var newSelectedTasks = {};
      var modelTasks = this.props.collection;

      // Note: not an **exact** check for all tasks being selected but a good
      // enough proxy.
      var allTasksSelected = Object.keys(this.state.selectedTasks).length ===
        modelTasks.length;

      if (!allTasksSelected) {
        modelTasks.forEach(function(task) { newSelectedTasks[task.id] = true; });
      }

      this.setState({selectedTasks: newSelectedTasks});
    },
    onTaskToggle: function(task, value) {
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
    render: function() {
      var selectedTasksLength = Object.keys(this.state.selectedTasks).length;

      if (selectedTasksLength === 0) {
        buttons =
          <p>
            <button className="btn btn-sm btn-default" onClick={this.props.fetchTasks}>
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
        <div>
          {buttons}
          <TaskListComponent tasks={this.props.collection}
            fetchState={this.props.fetchState}
            selectedTasks={this.state.selectedTasks}
            onTaskToggle={this.onTaskToggle}
            toggleAllTasks={this.toggleAllTasks}
            onTaskDetailSelect={this.props.onTaskDetailSelect} />
        </div>
      );
    }
  });
});
