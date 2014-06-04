/** @jsx React.DOM */

define([
  "React"
], function(React) {

  return React.createClass({
    displayName: "StackedViewComponent",
    getInitialState: function () {
      return {
        activeViewIndex: 0,
        viewStack: [0]
      };
    },
    setActiveViewIndex: function (index) {
      this.setState(React.addons.update(this.state, {
        activeViewIndex: {$set: index},
        viewStack: {$push: [index]}
      }));
    },
    popView: function() {
      this.state.viewStack.pop();
      this.setState({activeViewIndex: this.state.viewStack[this.state.viewStack.length - 1]});
    },

    render: function() {
      return (
        <div>
          {this.props.children[this.state.activeViewIndex]}
        </div>
      );
    }
  });
});
