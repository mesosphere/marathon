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
        showTimestamps: false
      };
    },
    getResource: function() {
      return this.props.collection;
    },
    mixins: [BackboneMixin],
    render: function() {
      var taskNodes;
      var _this = this;
      var tasksLength = this.props.collection.length;

      // If there are no tasks, they can't all be selected. Otherwise, assume
      // they are all selected and let the iteration below decide if that is
      // true.
      var allTasksSelected = tasksLength > 0;

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
          var isActive = this.props.selectedTasks[task.id] === true;
          if (!isActive) { allTasksSelected = false; }

          return (
            <TaskListItemComponent
              isActive={isActive}
              key={task.cid}
              onToggle={this.props.onTaskToggle}
              task={task} />
          );
        }, this);
      }

      var sortKey = this.props.collection.sortKey;
      var sortOrder =
        this.props.collection.sortReverse ?
        "▲" :
        "▼";
      return (
        <table className="table table-selectable">
          <thead>
            <tr>
              <th style={{width: "1px"}}>
                <input type="checkbox"
                  checked={allTasksSelected}
                  disabled={tasksLength === 0}
                  onChange={this.props.onAllTasksToggle} />
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
            </tr>
          </thead>
          <tbody>
            {taskNodes}
          </tbody>
        </table>
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
