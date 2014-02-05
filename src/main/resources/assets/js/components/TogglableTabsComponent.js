/** @jsx React.DOM */

define([
  "React",
  "Underscore",
  "jsx!components/NavTabsComponent"
], function(React, _, NavTabsComponent) {
  return React.createClass({
    getInitialState: function() {
      return {
        activeTabId:
          _.isArray(this.props.children) ?
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
      var childNodes;
      if (_.isArray(this.props.children)) {
        childNodes = this.props.children.map(function(child) {
          child.props.isActive = (child.props.id === this.state.activeTabId);
          return child;
        }, this);
      } else {
        this.props.children.props.isActive =
          this.props.children.props.id === this.state.activeTabId;
        childNodes = this.props.children;
      }

      return (
        <div className={this.props.className}>
          <NavTabsComponent
            activeTabId={this.state.activeTabId}
            onTabClick={this.onTabClick}
            tabs={this.props.tabs} />
          <div className="tab-content">
            {this.props.children}
          </div>
        </div>
      );
    }
  });
});
