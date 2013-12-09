/** @jsx React.DOM */

define([
  "jquery",
  "React"
], function($, React) {
  return React.createClass({
    destroy: function(event) {
      if (event.target.className === "modal" ||
          event.target.className === "modal-dialog") {
        var domNode = this.getDOMNode();

        // TODO(ssorallen): Why does this need to unmount from the parentNode?
        // If it is unmounted from `domNode`, the second render throws an
        // invariant exception.
        React.unmountComponentAtNode(domNode.parentNode);

        $(domNode).remove();
      }
    },
    render: function() {
      return (
        <div>
          <div className="modal" onClick={this.destroy}>
            <div className="modal-dialog">
              <div className="modal-content">
                {this.props.children}
              </div>
            </div>
          </div>
          <div className="modal-backdrop fade in"></div>
        </div>
      );
    }
  });
});
