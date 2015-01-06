/** @jsx React.DOM */

define([
  "Underscore",
  "React"
], function (_, React) {
  "use strict";

  return React.createClass({
    propTypes: {
      errors: React.PropTypes.array,
      attribute: React.PropTypes.bool.isRequired,
      children: React.PropTypes.component.isRequired,
      model: React.PropTypes.object.isRequired
    },

    handleChange: function(event) {
      var value = event.target.value;
      if (this.props.converter) {
        value = this.props.converter.forModel(value);
      }

      this.props.model.set(this.props.attribute, value);
      if (_.isFunction(this.props.onChange)) {
        this.props.onChange();
      }

      this.forceUpdate();
    },

    handleValueClick: function() {
      this.setState({ isEditing: true });
    },

    handleSaveClick: function() {
      this.setState({ isEditing: false });
    },

    handleModelSync: function() {
      this.setState({ isEditing: false });
    },

    getInitialState: function() {
      return { isEditing: false };
    },

    getValue: function() {
      var value = this.props.model.get(this.props.attribute);
      if (this.props.converter && value) {
        value = this.props.converter.forRender(value);
      }

      return value;
    },

    render: function() {
      var formComponent = this.state.isEditing ? this.renderFormControl() : this.renderValue();
      var label         = this.renderLabel();
      var classes       = this.props.error ? "has-error clearfix" : "clearfix";

      return <div className={classes}>
        {label}
        {formComponent}
      </div>;
    },

    componentWillMount: function() {
      this.props.model.on("sync", this.handleModelSync);
    },

    componentWillUnmount: function() {
      this.props.model.off("sync", this.handleModelSync);
    },

    renderFormControl: function() {
      var formControl = React.addons.cloneWithProps(
        React.Children.only(this.props.children),
        {
          className: "form-control",
          id: this.props.attribute + "-field",
          name: this.props.attribute,
          onChange: this.handleChange,
          value: this.getValue(),
          ref: "formControl"
        }
      );

      var error = this.props.error ? <p><strong className="text-danger">{this.props.error.message}</strong></p> : null;

      return (
        <dd onBlur={this.handleBlur}>
          {formControl}
          {error}
        </dd>
      );
    },

    renderValue: function() {
      var value   = this.getValue();
      var classes = value ? "" : "text-muted";

      value = value ? value : "Unspecified";

      return <dd className={classes} onClick={this.handleValueClick}>{value}</dd>;
    },

    renderLabel: function() {
      return <dt onClick={this.handleValueClick}><label htmlFor={this.props.attribute} className="control-label">{this.props.label}</label></dt>;
    }
  });

});
