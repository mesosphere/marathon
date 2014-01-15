/** @jsx React.DOM */

define([
  "React",
  "mixins/BackboneMixin"
], function(React, BackboneMixin) {
  var STATE_LOADING = 0;
  var STATE_ERROR = 1;
  var STATE_SUCCESS = 2;

  return React.createClass({
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
        fetchState: STATE_LOADING
      };
    },
    getResource: function() {
      return this.props.collection;
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

      if (this.state.fetchState === STATE_LOADING) {
        taskNodes =
          <tr>
            <td className="text-center" colSpan="4">
              Loading...
            </td>
          </tr>;
      } else if (this.state.fetchState === STATE_ERROR) {
        taskNodes =
          <tr>
            <td className="text-center text-danger" colSpan="4">
              Error fetching tasks. Refresh the list to try again.
            </td>
          </tr>;
      } else if (this.props.collection.length === 0) {
        taskNodes =
          <tr>
            <td className="text-center" colSpan="4">
              No tasks running.
            </td>
          </tr>;
      } else {
        taskNodes = this.props.collection.map(function(task) {
          var active = false;
          var className;

          // Expicitly check for Boolean since the key might not exist in the
          // object.
          if (_this.props.selectedTasks[task.id] === true) {
            active = true;
            className = "active";
          }

          return (
            <tr key={task.cid} className={className} onClick={_this.handleTrClick.bind(this, task)}>
              <td width="1">
                <input type="checkbox"
                  checked={active}
                  onChange={_this.props.onTaskSelect.bind(this, task)} />
              </td>
              <td>{task.get("id")}</td>
              <td>{task.get("host")}</td>
              <td>{task.get("ports").join(",")}</td>
            </tr>
          );
        });
      }

      return (
        <table className="table table-selectable">
          <thead>
            <tr>
              <th></th>
              <th>ID</th>
              <th>Hosts</th>
              <th>Ports</th>
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
    }
  });
});
