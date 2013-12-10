/** @jsx React.DOM */

define([
  "React",
  "jsx!components/ModalComponent",
  "jsx!components/TaskListComponent",
  "mixins/BackboneMixin"
], function(React, ModalComponent, TaskListComponent, BackboneMixin) {
  return React.createClass({
    destroy: function() {
      this.refs.modalComponent.destroy();
    },
    destroyApp: function() {
      if (confirm("Destroy app '" + this.props.model.get("id") + "'?\nThis is irreversible.")) {
        this.props.model.destroy();
        this.refs.modalComponent.destroy();
      }
    },
    mixins: [BackboneMixin],
    render: function() {
      var model = this.props.model;

      return (
        <ModalComponent ref="modalComponent">
          <div className="modal-header">
             <button type="button" className="close"
                aria-hidden="true" onClick={this.destroy}>&times;</button>
            <h3 className="modal-title">{model.get("id")}</h3>
          </div>
          <div className="modal-body">
            <dl className="dl-horizontal">
              <dt>CMD:</dt><dd>{model.get("cmd")}</dd>
              <dt>URIs:</dt><dd>{model.get("uris").length}</dd>
              <dt>Memory (MB):</dt><dd>{model.get("mem")}</dd>
              <dt>CPUs:</dt><dd>{model.get("cpus")}</dd>
              <dt>Instances:</dt><dd>{model.get("instances")}</dd>
            </dl>
            <TaskListComponent collection={model.get("tasks")} />
          </div>
          <div className="modal-footer">
            <button className="btn btn-sm btn-danger" onClick={this.destroyApp}>
              DESTROY
            </button>
            <button className="btn btn-sm btn-default"
                onClick={this.suspendApp} disabled={this.props.model.get("instances") < 1}>
              SUSPEND
            </button>
            <button className="btn btn-sm btn-default" onClick={this.scaleApp}>
              SCALE
            </button>
          </div>
        </ModalComponent>
      );
    },
    scaleApp: function() {
      var instances = prompt("Scale to how many instances?",
        this.props.model.get("instances"));

      if (instances != null) {
        this.props.model.scale(instances);
      }
    },
    suspendApp: function() {
      if (confirm("Suspend app by scaling to 0 instances?")) {
        this.props.model.scale(0);
      }
    }
  });
});
