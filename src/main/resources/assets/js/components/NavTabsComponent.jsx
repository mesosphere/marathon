/** @jsx React.DOM */

var React = require("react/addons");

function noop() {}

var NavTabsComponent = React.createClass({
  name: "NavTabsComponent",

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
    var tabsLeft = [];
    var tabsRight = [];
    var tabs = [];

    this.props.tabs.map(function (tab) {
      var tabClassSet = React.addons.classSet({
        "active": tab.id === activeTabId
      });

      /* jshint trailing:false, quotmark:false, newcap:false */
      /* jscs:disable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
      var badge = tab.badge > 0 ?
        <span className="badge">{tab.badge}</span> :
        null;

      var tabelement = (
        <li className={tabClassSet} key={tab.id}>
          <a href={"#" + tab.id} onClick={this.onTabClick.bind(this, tab.id)}>
            {tab.text}
          </a>
          {badge}
        </li>
      );

      if (!tab.alignRight) {
        tabsLeft.push(tabelement);
      } else {
        tabsRight.push(tabelement);
      }
    }, this);

    if (tabsLeft.length) {
      tabs.push((
        <ul className={this.props.className + " nav navbar navbar-static-top nav-tabs"} key="tabsLeft">
          {tabsLeft}
        </ul>
      ));
    }

    if (tabsRight.length) {
      tabs.push((
        <ul className={this.props.className + " nav navbar navbar-static-top nav-tabs navbar-right"} key="tabsRight">
          {tabsRight}
        </ul>
      ));
    }

    return (
      <div>
        {tabs}
      </div>
    );
  }
});

module.exports = NavTabsComponent;
