/** @jsx React.DOM */

define([
  "React"
], function(React) {
  return React.createClass({
    onInputChange: function(event) {
      this.props.model.set(event.target.name, event.target.value);
    },

    render: function() {
      var errorBlock, errors, helpBlock;

      var attribute = this.props.attribute;
      var className = "form-group";
      var fieldId = attribute + "-field";

      // Find any errors matching this attribute.
      if (this.props.model.validationError != null) {
        errors = this.props.model.validationError.filter(function(e) {
          return (e.attribute === attribute);
        });
      }

      if (errors != null && errors.length > 0) {
        className += " has-error";
        errorBlock = errors.map(function(error) {
          return <div className="help-block"><strong>{error.message}</strong></div>;
        });
      }

      if (this.props.help != null) {
        helpBlock = <div className="help-block">{this.props.help}</div>;
      }

      // Assume there is a single child of either <input> or <textarea>, and add
      // the needed props to make it an input for this attribute.
      this.props.children.props.className = "form-control";
      this.props.children.props.id = fieldId;
      this.props.children.props.name = attribute;
      this.props.children.props.value = this.props.model.get(attribute);
      this.props.children.props.onChange = this.onInputChange;

      return (
        <div className={className}>
          <label htmlFor={fieldId} className="col-md-3 control-label">
            {this.props.label}
          </label>
          <div className="col-md-9">
            {this.props.children}
            {helpBlock}
            {errorBlock}
          </div>
        </div>
      );
    }
  });
});
