/** @jsx React.DOM */

"use strict";

var React = require("react/addons");

var NavTabs = require("../components/NavTabs");

var TogglableTabs = React.createClass({
  name: "TogglableTabs",

  propTypes: {
    activeTabId: React.PropTypes.string.isRequired,
    className: React.PropTypes.string,
    onTabClick: React.PropTypes.func,
    tabs: React.PropTypes.array
  },

  render: function () {
    var childNodes = React.Children.map(this.props.children, function (child) {
      return React.addons.cloneWithProps(child, {
        isActive: (child.props.id === this.props.activeTabId)
      });
    }, this);

    var nav;
    if (this.props.onTabClick != null && this.props.tabs != null) {
      /* jscs:disable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
      /* jshint trailing:false, quotmark:false, newcap:false */
      nav = (
        <NavTabs
          activeTabId={this.props.activeTabId}
          onTabClick={this.props.onTabClick}
          tabs={this.props.tabs} />
      );
    }

    /* jscs:disable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
    /* jshint trailing:false, quotmark:false, newcap:false */
    return (
      <div className={this.props.className}>
        {nav}
        <div className="tab-content">
          {childNodes}
        </div>
      </div>
    );
  }
});

module.exports = TogglableTabs;
