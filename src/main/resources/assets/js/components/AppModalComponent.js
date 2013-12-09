/** @jsx React.DOM */

define([
  "React",
  "jsx!components/ModalComponent",
  "jsx!components/TaskListComponent"
], function(React, ModalComponent, TaskListComponent) {
  return React.createClass({
    render: function() {
      var model = this.props.model;

      return (
        <ModalComponent>
          <div className="row">
            <div className="col-md-6">
              {model.get("id")}
            </div>
            <div className="col-md-6 text-right">
              <button className="btn btn-xs btn-default">SCALE</button>
              <button className="btn btn-xs btn-default">SUSPEND</button>
              <button className="btn btn-xs btn-danger">DESTROY</button>
            </div>
          </div>
          <dl className="dl-horizontal">
            <dt>CMD:</dt><dd>{model.get("cmd")}</dd>
            <dt>URIs:</dt><dd>{model.get("uris").length}</dd>
            <dt>Memory (MB):</dt><dd>{model.get("mem")}</dd>
            <dt>CPUs:</dt><dd>{model.get("cpus")}</dd>
            <dt>Instances:</dt><dd>{model.get("instances")}</dd>
          </dl>
          <TaskListComponent collection={model.get("tasks")} />
        </ModalComponent>
      );
    }
  });
});
