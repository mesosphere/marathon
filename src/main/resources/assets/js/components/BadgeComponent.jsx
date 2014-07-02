/** @jsx React.DOM */

define([
  "React"
], function(React) {
  return React.createClass({
    displayName: "BadgeComponent",
    propTypes: {
      type: React.PropTypes.string
    },

    render: function() {
      var hasType = (this.props.type != null) && (this.props.type !== "");

      var classSet = React.addons.classSet({
        "badge badge-circlet": true,
        "badge-circlet-default": hasType
      });

      return (
        <span className={classSet}>
          {hasType ? <span className={"health-dot health-dot-" + this.props.type}></span> : null}
          {this.props.children}
        </span>
      );
    }
  });
});
