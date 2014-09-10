/** @jsx React.DOM */

define([
  "React"
], function(React) {
  "use strict";

  function noop() {}

  return React.createClass({
    name: "NavTabsComponent",

    propTypes: {
      activeTabId: React.PropTypes.string.isRequired,
      className: React.PropTypes.string,
      onTabClick: React.PropTypes.func.isRequired,
      tabs: React.PropTypes.array.isRequired
    },

    getDefaultProps: function() {
      return {
        onTabClick: noop
      };
    },

    onTabClick: function(id, event) {
      event.preventDefault();
      this.props.onTabClick(id);
    },

    render: function() {
      var activeTabId = this.props.activeTabId;

      var tabs = this.props.tabs.map(function(tab) {
        var tabClassSet = React.addons.classSet({
          " active": tab.id === activeTabId
        });

        return (
          <li className={tabClassSet} key={tab.id}>
            <a href={"#" + tab.id} onClick={this.onTabClick.bind(this, tab.id)}>
              {tab.text}
            </a>
          </li>
        );
      }, this);

      /* jshint trailing:false, quotmark:false, newcap:false */
      return (
        <ul className={this.props.className + " nav nav-tabs"}>
          {tabs}
        </ul>
      );
    }
  });
});
