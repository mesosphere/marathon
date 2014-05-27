/** @jsx React.DOM */

define([
  "React"
], function(React) {
  return React.createClass({
    getDefaultProps: function() {
      return {
        isActive: false
      };
    },
    render: function() {
      var classSet = React.addons.classSet({
        "active": this.props.isActive,
        "tab-pane": true
      });

      return (
        <div className={classSet}>
          {this.props.children}
        </div>
      );
    }
  });
});
