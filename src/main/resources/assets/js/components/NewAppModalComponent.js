/** @jsx React.DOM */

define([
  "jquery",
  "React",
  "mixins/BackboneMixin",
  "jsx!components/ModalComponent"
], function($, React, BackboneMixin, ModalComponent) {
  return React.createClass({
    destroy: function() {
      this.refs.modalComponent.destroy();
    },
    mixins: [BackboneMixin],
    onChange: function(event) {
      this.props.model.set(event.target.name, event.target.value);
    },
    onSubmit: function(event) {
      event.preventDefault();

      var attrArray = $(event.target).serializeArray();
      var modelAttrs = {};

      for (var i = 0; i < attrArray.length; i++) {
        var val = attrArray[i];
        if (val.value !== "") modelAttrs[val.name] = val.value;
      }

      // URIs should be an Array of Strings.
      if ("uris" in modelAttrs) modelAttrs.uris = modelAttrs.uris.split(",");

      // mem, cpus, and instances are all Numbers and should be parsed as such.
      if ("mem" in modelAttrs) modelAttrs.mem = parseFloat(modelAttrs.mem);
      if ("cpus" in modelAttrs) modelAttrs.cpus = parseFloat(modelAttrs.cpus);
      if ("instances" in modelAttrs) {
        modelAttrs.instances = parseInt(modelAttrs.instances, 10);
      }

      this.props.onCreate(modelAttrs);
      this.destroy();
    },
    render: function() {
      var model = this.props.model;

      return (
        <ModalComponent ref="modalComponent">
          <form method="post" className="form-horizontal" role="form" onSubmit={this.onSubmit}>
            <div className="modal-header">
              <h3 className="modal-title">New Application</h3>
            </div>
            <div className="modal-body">
              <div className="form-group">
                <label htmlFor="id-field" className="col-md-3 control-label">
                  ID
                </label>
                <div className="col-md-9">
                  <input className="form-control" id="id-field" name="id" value={model.get("id")}
                    autoFocus required onChange={this.onChange} />
                </div>
              </div>
              <div className="form-group">
                <label htmlFor="cmd-field" className="col-md-3 control-label">
                  Command
                </label>
                <div className="col-md-9">
                  <input className="form-control" id="cmd-field" name="cmd" required />
                </div>
              </div>
              <div className="form-group">
                <label htmlFor="cpus-field" className="col-md-3 control-label">
                  CPUs
                </label>
                <div className="col-md-9">
                  <input className="form-control" id="cpus-field" value={model.get("cpus")}
                    name="cpus" min="0" step="any" type="number" required onChange={this.onChange} />
                </div>
              </div>
              <div className="form-group">
                <label htmlFor="mem-field" className="col-md-3 control-label">
                  Memory (MB)
                </label>
                <div className="col-md-9">
                  <input className="form-control" id="mem-field" value={model.get("mem")}
                    name="mem" min="0" step="any" type="number" required onChange={this.onChange} />
                </div>
              </div>
              <div className="form-group">
                <label htmlFor="instances-field" className="col-md-3 control-label">
                  Instances
                </label>
                <div className="col-md-9">
                  <input className="form-control" id="instances-field" value={model.get("instances")}
                    name="instances" min="1" step="1" type="number" required onChange={this.onChange} />
                </div>
              </div>
              <div className="form-group">
                <label htmlFor="uris-field" className="col-md-3 control-label">
                  URIs
                </label>
                <div className="col-md-9">
                  <input className="form-control" id="uris-field" name="uris" />
                </div>
              </div>
            </div>
            <div className="modal-footer">
              <button className="btn btn-link" type="button" onClick={this.destroy}>
                Cancel
              </button>
              <input type="submit" className="btn btn-primary" value="Create" />
            </div>
          </form>
        </ModalComponent>
      );
    }
  });
});
