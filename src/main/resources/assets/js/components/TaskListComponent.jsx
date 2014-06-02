/** @jsx React.DOM */

define([
  "React",
  "mixins/BackboneMixin",
  "jsx!components/TaskListItemComponent"
], function(React, BackboneMixin, TaskListItemComponent) {
  var STATE_LOADING = 0;
  var STATE_ERROR = 1;
  var STATE_SUCCESS = 2;

  var UPDATE_INTERVAL = 2000;

  return React.createClass({
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
    fetchTasks: function() {
      var _this = this;

      this.props.collection.fetch({
        error: function() {
          _this.setState({fetchState: STATE_ERROR});
        },
        reset: true,
        success: function() {
          _this.setState({fetchState: STATE_SUCCESS});
        }
      });
    },
    getInitialState: function() {
      return {
        fetchState: STATE_LOADING,
        showTimestamps: false,
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
    handleThToggleClick: function(event) {
      // If the click happens on the checkbox, let the checkbox's onchange event
      // handler handle it and skip handling the event here.
      if (event.target.nodeName !== "INPUT") {
        this.toggleAllTasks();
      }
    },
    render: function() {
      var taskNodes;
      var _this = this;
      var tasksLength = this.props.collection.length;
      var selectedTasksLength = Object.keys(this.state.selectedTasks).length;

      // If there are no tasks, they can't all be selected. Otherwise, assume
      // they are all selected and let the iteration below decide if that is
      // true.
      var allTasksSelected = tasksLength > 0;

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

      if (this.state.fetchState === STATE_LOADING) {
        taskNodes =
          <tr>
            <td className="text-center text-muted" colSpan="5">
              Loading tasks...
            </td>
          </tr>;
      } else if (this.state.fetchState === STATE_ERROR) {
        taskNodes =
          <tr>
            <td className="text-center text-danger" colSpan="5">
              Error fetching tasks. Refresh the list to try again.
            </td>
          </tr>;
      } else if (tasksLength === 0) {
        taskNodes =
          <tr>
            <td className="text-center" colSpan="5">
              No tasks running.
            </td>
          </tr>;
      } else {
        taskNodes = this.props.collection.map(function(task) {
          // Expicitly check for Boolean since the key might not exist in the
          // object.
          var isActive = this.state.selectedTasks[task.id] === true;
          if (!isActive) { allTasksSelected = false; }

          return (
              <TaskListItemComponent
                task={task}
                isActive={isActive}
                key={task.id}
                onToggle={this.onTaskToggle}
                onTaskDetailSelect={this.props.onTaskDetailSelect} />
          );
        }, this);
      }

      var sortKey = this.props.collection.sortKey;
      var sortOrder =
        this.props.collection.sortReverse ?
        "▲" :
        "▼";
      return (
        <div>
          {buttons}
          <table className="table">
            <thead>
              <tr>
                <th className="clickable" width="1" onClick={this.handleThToggleClick}>
                  <input type="checkbox"
                    checked={allTasksSelected}
                    disabled={tasksLength === 0}
                    onChange={this.toggleAllTasks} />
                </th>
                <th>
                  <span onClick={this.sortCollectionBy.bind(null, "id")}
                        className="clickable">
                    ID {(sortKey === "id") ? sortOrder : null}
                  </span>
                </th>
                <th>
                  <span onClick={this.sortCollectionBy.bind(null, "status")}
                        className="clickable">
                    Status {(sortKey === "status") ? sortOrder : null}
                  </span>
                </th>
                <th className="text-right">
                  <span onClick={this.sortCollectionBy.bind(null, "updatedAt")}
                        className="clickable">
                    {(sortKey === "updatedAt") ? sortOrder : null} Updated
                  </span>
                </th>
                <th className="text-center">
                  <span onClick={this.sortCollectionBy.bind(null, "health")}
                        className="clickable">
                    {(sortKey === "health") ? sortOrder : null} Health
                  </span>
                </th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {taskNodes}
            </tbody>
          </table>
        </div>
      );
    },
    setFetched: function() {
      this.setState({fetched: true});
    },
    sortCollectionBy: function(comparator) {
      var collection = this.props.collection;
      comparator =
        collection.sortKey === comparator && !collection.sortReverse ?
        "-" + comparator :
        comparator;
      collection.setComparator(comparator);
      collection.sort();
    },
    startPolling: function() {
      if (this._interval == null) {
        this._interval = setInterval(this.fetchTasks, UPDATE_INTERVAL);
      }
    },
    stopPolling: function() {
      clearInterval(this._interval);
      this._interval = null;
    },
    toggleShowTimestamps: function() {
      this.setState({showTimestamps: !this.state.showTimestamps});
    }
  });
});
