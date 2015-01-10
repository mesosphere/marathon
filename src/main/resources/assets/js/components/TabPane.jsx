/** @jsx React.DOM */

var React = require("react/addons");

module.exports = React.createClass({
  name: "TabPane",

  propTypes: {
    isActive: React.PropTypes.bool,
    onActivate: React.PropTypes.func
  },

  componentDidUpdate: function (prevProps) {
    if (!prevProps.isActive && this.props.isActive) {
      this.props.onActivate();
    }
  },

  getDefaultProps: function () {
    return {
      isActive: false,
      onActivate: function () {}
    };
  },

  render: function () {
    var classSet = React.addons.classSet({
      "active": this.props.isActive,
      "tab-pane": true
    });

    /* jscs:disable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
    /* jshint trailing:false, quotmark:false, newcap:false */
    return (
      <div className={classSet}>
        {this.props.children}
      </div>
    );
  }
});
