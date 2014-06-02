/** @jsx React.DOM */

define([
  "React"
], function(React) {
  var VIEW_STACK = [];

  return React.createClass({
    displayName: "StackedViewComponent",
    getInitialState: function () {
      VIEW_STACK.push(0);
      return {
        activeViewIndex: 0
      };
    },
    setActiveViewIndex: function (index) {
      this.setState({activeViewIndex: index});
      VIEW_STACK.push(index);
    },
    popView: function() {
      VIEW_STACK.pop();
      this.setState({activeViewIndex: VIEW_STACK[VIEW_STACK.length - 1]});
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
