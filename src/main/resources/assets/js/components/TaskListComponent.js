/** @jsx React.DOM */

define([
  "React",
  "mixins/BackboneMixin"
], function(React, BackboneMixin) {
  return React.createClass({
    componentWillMount: function() {
      this.props.collection.on("reset", this.setFetched, this);
      this.props.collection.fetch({reset: true});
    },
    componentWillUnmount: function() {
      this.props.collection.off("reset", this.setFetched);
    },
    getInitialState: function() {
      return {
        fetched: false
      };
    },
    mixins: [BackboneMixin],
    render: function() {
      var taskNodes;
      if (!this.state.fetched) {
        taskNodes =
          <tr>
            <td className="text-center" colSpan="3">
              Loading...
            </td>
          </tr>;
      } else if (this.props.collection.length === 0) {
        taskNodes =
          <tr>
            <td className="text-center" colSpan="3">
              No tasks running
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
