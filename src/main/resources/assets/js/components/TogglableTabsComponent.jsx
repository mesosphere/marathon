/** @jsx React.DOM */

var React = require("react/addons");
var NavTabsComponent = require("../components/NavTabsComponent");

module.exports = React.createClass({
    name: "TogglableTabsComponent",

    propTypes: {
      activeTabId: React.PropTypes.string.isRequired,
      className: React.PropTypes.string,
      tabs: React.PropTypes.array
    },

    getDefaultProps: function () {
      return {
        tabs: []
      };
    },

    render: function () {
      var childNodes = React.Children.map(this.props.children,
        function (child) {
          return React.addons.cloneWithProps(child, {
            isActive: (child.props.id === this.props.activeTabId)
          });
        }, this);

      var navTabsClassSet = React.addons.classSet({
        "hidden": this.props.tabs.length === 0
      });

      /* jshint trailing:false, quotmark:false, newcap:false */
      /* jscs:disable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
      return (
        <div className={this.props.className}>
          <NavTabsComponent
            className={navTabsClassSet}
            activeTabId={this.props.activeTabId}
            onTabClick={this.props.onTabClick}
            tabs={this.props.tabs} />
          <div className="tab-content">
            {childNodes}
          </div>
        </div>
      );
    }
  });
