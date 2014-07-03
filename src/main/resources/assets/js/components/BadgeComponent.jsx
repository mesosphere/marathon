/** @jsx React.DOM */

define([
  "React"
], function(React) {
  var TYPES = {
    "error": "health-dot-error",
    "warning": "health-dot-warning"
  };

  return React.createClass({
    displayName: "BadgeComponent",
    propTypes: {
      types: React.PropTypes.object
    },

    render: function() {
      var healthDot;
      var hasType = Object.keys(TYPES).some(function(k) {
        var healthDotClassObj;

        // Assumes only one type should be active at a time. If more than one
        // value is truthy, the health dot might not be the one expected.
        if (this.props.types[k]) {
          healthDotClassObj = {"health-dot": true};
          healthDotClassObj[TYPES[k]] = true;

          healthDot = (
            <span className={React.addons.classSet(healthDotClassObj)}></span>
          );

          return true;
        }
      }, this);

      var classSet = React.addons.classSet({
        "badge badge-circlet": true,
        "badge-circlet-default": hasType
      });

      return (
        <span className={classSet}>
          {healthDot}
          {this.props.children}
        </span>
      );
    }
  });
});
