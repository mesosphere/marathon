/** @jsx React.DOM */

define([
  "jquery",
  "Underscore",
  "React",
  "mixins/BackboneMixin",
  "models/App",
  "jsx!components/FormGroupComponent",
  "jsx!components/ModalComponent"
], function($, _, React, BackboneMixin, App, FormGroupComponent,
      ModalComponent) {

  return React.createClass({
    mixins: [BackboneMixin],
    propTypes: {
      onCreate: React.PropTypes.func,
      onDestroy: React.PropTypes.func
    },

    getDefaultProps: function() {
      return {
        onCreate: $.noop,
        onDestroy: $.noop
      };
    },

    getInitialState: function() {
      return {
        model: new App()
      };
    },

    destroy: function() {
      // This will also call `this.props.onDestroy` since it is passed as the
      // callback for the modal's `onDestroy` prop.
      this.refs.modalComponent.destroy();
    },

    getResource: function() {
      return this.state.model;
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

      // Ports should always be an Array.
      if ("ports" in modelAttrs) {
        var portStrings = modelAttrs.ports.split(",");
        modelAttrs.ports = _.map(portStrings, function(p) {
          var port = parseInt(p, 10);
          return _.isNaN(port) ? p : port;
        });
      } else {
        modelAttrs.ports = [];
      }

      // mem, cpus, and instances are all Numbers and should be parsed as such.
      if ("mem" in modelAttrs) modelAttrs.mem = parseFloat(modelAttrs.mem);
      if ("cpus" in modelAttrs) modelAttrs.cpus = parseFloat(modelAttrs.cpus);
      if ("disk" in modelAttrs) modelAttrs.disk = parseFloat(modelAttrs.disk);
      if ("instances" in modelAttrs) {
        modelAttrs.instances = parseInt(modelAttrs.instances, 10);
      }

      this.state.model.set(modelAttrs);

      if (this.state.model.isValid()) {
        this.props.onCreate(this.state.model);
        this.destroy();
      }
    },

    render: function() {
      var model = this.state.model;

      return (
        <ModalComponent ref="modalComponent" onDestroy={this.props.onDestroy}>
          <form method="post" role="form" onSubmit={this.onSubmit}>
            <div className="modal-header">
              <button type="button" className="close"
                aria-hidden="true" onClick={this.destroy}>&times;</button>
              <h3 className="modal-title">New Application</h3>
            </div>
            <div className="modal-body">
              <FormGroupComponent
                  attribute="id"
                  label="ID"
                  model={model}>
                <input
                  autoFocus
                  pattern={App.VALID_ID_PATTERN}
                  required
                  title="Must be a valid hostname (may contain only digits, dashes, dots, and lowercase letters)" />
              </FormGroupComponent>
              <FormGroupComponent
                  attribute="cpus"
                  label="CPUs"
                  model={model}>
                <input min="0" step="any" type="number" required />
              </FormGroupComponent>
              <FormGroupComponent
                  attribute="mem"
                  label="Memory (MB)"
                  model={model}>
                <input min="0" step="any" type="number" required />
              </FormGroupComponent>
              <FormGroupComponent
                  attribute="disk"
                  label="Disk Space (MB)"
                  model={model}>
              <input min="0" step="any" type="number" required />
              </FormGroupComponent>
              <FormGroupComponent
                  attribute="instances"
                  label="Instances"
                  model={model}>
                <input min="1" step="1" type="number" required />
              </FormGroupComponent>
              <hr />
              <h4>Optional Settings</h4>
              <FormGroupComponent
                  attribute="cmd"
                  label="Command"
                  model={model}>
                <textarea style={{resize: "vertical"}} />
              </FormGroupComponent>
              <FormGroupComponent
                  attribute="executor"
                  label="Executor"
                  model={model}>
                <input />
              </FormGroupComponent>
              <FormGroupComponent
                  attribute="ports"
                  help="Comma-separated list of numbers. 0's (zeros) assign random ports. (Default: one random port)"
                  label="Ports"
                  model={model}>
                <input />
              </FormGroupComponent>
              <FormGroupComponent
                  attribute="uris"
                  help="Comma-separated list of valid URIs."
                  label="URIs"
                  model={model}>
                <input />
              </FormGroupComponent>
              <div>
                <input type="submit" className="btn btn-success" value="+ Create" /> <button className="btn btn-default" type="button" onClick={this.destroy}>
                  Cancel
                </button>
              </div>
            </div>
          </form>
        </ModalComponent>
      );
    }
  });
});
