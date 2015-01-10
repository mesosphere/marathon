/** @jsx React.DOM */

var React = require("react/addons");

function noop() {}

var NavTabs = React.createClass({
  name: "NavTabs",

  propTypes: {
    activeTabId: React.PropTypes.string.isRequired,
    className: React.PropTypes.string,
    onTabClick: React.PropTypes.func.isRequired,
    tabs: React.PropTypes.array.isRequired
  },

  getDefaultProps: function () {
    return {
      onTabClick: noop,
      className: ""
    };
  },

  onTabClick: function (id, event) {
    event.preventDefault();
    this.props.onTabClick(id);
  },

  render: function () {
    var activeTabId = this.props.activeTabId;

    var tabs = this.props.tabs.map(function (tab) {
      var tabClassSet = React.addons.classSet({
        "active": tab.id === activeTabId
      });

      var badge = tab.badge > 0 ?
        <span className="badge">{tab.badge}</span> :
        null;

      return (
        /* jscs:disable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
        /* jshint trailing:false, quotmark:false, newcap:false */
        <li className={tabClassSet} key={tab.id}>
          <a href={"#" + tab.id} onClick={this.onTabClick.bind(this, tab.id)}>
            {tab.text}
          </a>
          {badge}
        </li>
      );
    }, this);

    var classes = {
      "nav navbar navbar-static-top nav-tabs": true
    };

    if (this.props.className != null) {
      classes[this.props.className] = true;
    }

    var navTabsClassSet = React.addons.classSet(classes);

    /* jscs:disable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
    /* jshint trailing:false, quotmark:false, newcap:false */
    return (
      <ul className={navTabsClassSet}>
        {tabs}
      </ul>
    );
  }
});

module.exports = NavTabs;
