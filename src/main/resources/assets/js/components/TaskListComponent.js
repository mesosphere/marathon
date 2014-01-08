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
    mixins: [BackboneMixin],
    render: function() {
      var taskNodes;
      if (this.state.fetchState === STATE_LOADING) {
        taskNodes =
          <tr>
            <td className="text-center" colSpan="3">
              Loading...
            </td>
          </tr>;
      } else if (this.state.fetchState === STATE_ERROR) {
        taskNodes =
          <tr>
            <td className="text-center text-danger" colSpan="3">
              Error fetching tasks. Re-open the modal to try again.
            </td>
          </tr>;
      } else if (this.props.collection.length === 0) {
        taskNodes =
          <tr>
            <td className="text-center" colSpan="3">
              No tasks running.
            </td>
          </tr>;
      } else {
        taskNodes = this.props.collection.map(function(task) {
          return (
            <tr key={task.cid}>
              <td>{task.get("id")}</td>
              <td>{task.get("host")}</td>
              <td>{task.get("ports").join(",")}</td>
            </tr>
          );
        });
      }

      return (
        <table className="table task-table">
          <thead>
            <tr>
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
