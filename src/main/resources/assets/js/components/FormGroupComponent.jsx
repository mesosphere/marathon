/** @jsx React.DOM */

define([
  "React"
], function(React) {
  return React.createClass({
    propTypes: {
      children: React.PropTypes.component.isRequired,
      model: React.PropTypes.object.isRequired
    },

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
        helpBlock = <div className="text-muted help-block">{this.props.help}</div>;
      }

      // Assume there is a single child of either <input> or <textarea>, and add
      // the needed props to make it an input for this attribute.
      var formControlChild = React.addons.cloneWithProps(
        React.Children.only(this.props.children),
        {
          className: "form-control",
          id: fieldId,
          name: attribute,
          onChange: this.onInputChange,
          value: this.props.model.get(attribute)
        }
      );

      return (
        <div className={className}>
          <label htmlFor={fieldId} className="control-label">
            {this.props.label}
          </label>
          <div>
            {formControlChild}
            {helpBlock}
            {errorBlock}
          </div>
        </div>
      );
    }
  });
});
