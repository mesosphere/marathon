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
      tabs: React.PropTypes.array.isRequired
    },

    getDefaultProps: function() {
      return {
        className: ""
      };
    },

    render: function() {
      var activeTabId = this.props.activeTabId;

      var tabs = this.props.tabs.map(function(tab) {
        var tabClassSet = React.addons.classSet({
          "active": tab.id === activeTabId
        });

        var badge = tab.badge > 0 ?
          <span className="badge">{tab.badge}</span> :
          null;

        return (
          /* jshint trailing:false, quotmark:false, newcap:false */
          <li className={tabClassSet} key={tab.id}>
            <a href={"#" + tab.id}>
              {tab.text}
            </a>
            {badge}
          </li>
        );
      }, this);

      /* jshint trailing:false, quotmark:false, newcap:false */
      return (
        <ul className={this.props.className + " nav navbar navbar-static-top nav-tabs"}>
          {tabs}
        </ul>
      );
    }
  });
});
