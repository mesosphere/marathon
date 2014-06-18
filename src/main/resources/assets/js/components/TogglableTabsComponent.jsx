/** @jsx React.DOM */

define([
  "React",
  "Underscore",
  "jsx!components/NavTabsComponent"
], function(React, _, NavTabsComponent) {
  "use strict";

  return React.createClass({
    getInitialState: function() {
      return {
        activeTabId: _.isArray(this.props.children) ?
          this.props.children[0].props.id :
          this.props.children.props.id
      };
    },
    onTabClick: function(id) {
      this.setState({
        activeTabId: id
      });
    },
    render: function() {
      var childNodes = React.Children.map(this.props.children, function(child) {
        return React.addons.cloneWithProps(child, {
          isActive: (child.props.id === this.state.activeTabId)
        });
      }, this);

      return (
        <div className={this.props.className}>
          <NavTabsComponent
            activeTabId={this.state.activeTabId}
            onTabClick={this.onTabClick}
            tabs={this.props.tabs} />
          <div className="tab-content">
            {childNodes}
          </div>
        </div>
      );
    }
  });
});
