/** @jsx React.DOM */

var $ = require("jquery");
var React = require("react/addons");

var PageComponent = React.createClass({
  displayName: "PageComponent",
  propTypes: {
    onDestroy: React.PropTypes.func
  },

  destroy: function () {
    this.props.onDestroy();
  },

  getDefaultProps: function () {
    return {
      onDestroy: $.noop
    };
  },

  onClick: function (event) {
    var $target = $(event.target);

    if ($target.hasClass("page")) {
      this.destroy();
    }
  },

  render: function () {
    /* jshint trailing:false, quotmark:false, newcap:false */
    /* jscs:disable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
    return (
      <div>
        <div className="page">
          {this.props.children}
        </div>
      </div>
    );
  }
});

module.exports = PageComponent;
