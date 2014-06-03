/** @jsx React.DOM */

define([
  "React",
  "mixins/BackboneMixin",
  "jsx!components/TaskListComponent"
], function(React, BackboneMixin, TaskListComponent) {
  

  var UPDATE_INTERVAL = 2000;

  return React.createClass({
    displayName: "TaskViewComponent",
    mixins: [BackboneMixin],
    componentDidMount: function() {
      this.startPolling();
    },
    componentWillUnmount: function() {
      this.stopPolling();
    },
    componentWillMount: function() {
      this.fetchTasks();
    },
    getResource: function() {
      return this.props.collection;
    },
    getInitialState: function() {
      return {
        fetchState: TaskListComponent.STATES.STATE_LOADING,
        selectedTasks: {}
      };
    },
    fetchTasks: function() {
      var _this = this;

      this.props.collection.fetch({
        error: function() {
          _this.setState({fetchState: TaskListComponent.STATES.STATE_ERROR});
        },
        reset: true,
        success: function() {
          _this.setState({fetchState: TaskListComponent.STATES.STATE_SUCCESS});
        }
      });
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
            <button className="btn btn-sm btn-default" onClick={this.fetchTasks}>
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
            fetchState={this.state.fetchState}
            selectedTasks={this.state.selectedTasks}
            onTaskToggle={this.onTaskToggle}
            toggleAllTasks={this.toggleAllTasks}
            onTaskDetailSelect={this.props.onTaskDetailSelect} />
        </div>
      );
    },
    setFetched: function() {
      this.setState({fetched: true});
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
