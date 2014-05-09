/** @jsx React.DOM */

define([
  "React",
  "mixins/BackboneMixin"
], function(React, BackboneMixin) {
  var STATE_LOADING = 0;
  var STATE_ERROR = 1;
  var STATE_SUCCESS = 2;
  var UPDATE_INTERVAL = 2000;

  function taskHostPortsToString(task) {
    var portsString;
    var ports = task.get("ports");
    if (ports.length > 1) {
      portsString = ":[" + ports.join(",") + "]";
    } else if (ports.length === 1) {
      portsString = ":" + ports[0];
    } else {
      portsString = "";
    }

    return task.get("host") + portsString;
  }

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
    handleCheckboxClick: function(task, event) {
      this.props.onTaskToggle(task, event.target.checked);
    },
    handleTrClick: function(task, event) {
      // If the click happens on the checkbox, let the checkbox's onchange event
      // handler handle it and skip handling the event here.
      if (event.target.nodeName !== "INPUT") {
        this.props.onTaskToggle(task);
      }
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
            <td className="text-center" colSpan="5">
              Loading...
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
          var active = false;
          var className;

          // Expicitly check for Boolean since the key might not exist in the
          // object.
          if (this.props.selectedTasks[task.id] === true) {
            active = true;
            className = "active";
          } else {
            allTasksSelected = false;
          }

          var statusNode;
          if (task.isStarted()) {
            statusNode =
              <span className="badge badge-default">
                {task.get("status")}
              </span>;
          } else if (task.isStaged()) {
            statusNode =
              <span className="badge badge-warning">
                {task.get("status")}
              </span>;
          }

          var updatedAtNode;
          if (task.get("updatedAt") != null) {
            updatedAtNode =
              <time timestamp={task.get("updatedAt")}>
                {task.get("updatedAt").toISOString()}
              </time>;
          }

          return (
            <tr key={task.cid} className={className} onClick={this.handleTrClick.bind(this, task)}>
              <td width="1">
                <input type="checkbox"
                  checked={active}
                  onChange={this.handleCheckboxClick.bind(this, task)} />
              </td>
              <td>
                {task.get("id")}<br />
                <span className="text-muted">
                  {taskHostPortsToString(task)}
                </span>
              </td>
              <td>{statusNode}</td>
              <td className="text-right">{updatedAtNode}</td>
            </tr>
          );
        }, this);
      }

      var comparator = this.props.collection.comparatorAttribute;
      var sortSymbol =
        (this.props.collection.comparatorString.substr(0, 1) === "-") ?
        "▼" :
        "▲";
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
              <th onClick={this.sortCollectionBy.bind(null, "id")}>
                ID {(comparator === "id") ? sortSymbol : null}
              </th>
              <th onClick={this.sortCollectionBy.bind(null, "status")}>
                Status {(comparator === "status") ? sortSymbol : null}
              </th>
              <th className="text-right" onClick={this.sortCollectionBy.bind(null, "updatedAt")}>
                {(comparator === "updatedAt") ? sortSymbol : null} Updated
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
      if (comparator === this.props.collection.comparatorAttribute) {
        this.props.collection.sortReverse();
      } else {
        this.props.collection.setComparator(comparator);
        this.props.collection.sort();
      }
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
