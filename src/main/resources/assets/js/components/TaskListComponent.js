/** @jsx React.DOM */

define([
  "React",
  "mixins/BackboneMixin"
], function(React, BackboneMixin) {
  return React.createClass({
    componentWillMount: function() {
      this.props.collection.fetch({reset: true});
    },
    mixins: [BackboneMixin],
    render: function() {
      var taskNodes = this.props.collection.map(function(task) {
        return (
          <tr key={task.cid}>
            <td>{task.get("id")}</td>
            <td>{task.get("host")}</td>
            <td>{task.get("ports").join(",")}</td>
          </tr>
        );
      });

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
    }
  });
});
