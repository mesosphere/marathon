/** @jsx React.DOM */

define([
  "React"
], function(React) {
  return React.createClass({
    destroy: function(event) {
      if (event.target.className === "modal" ||
          event.target.className === "modal-dialog") {
        var domNode = this.getDOMNode();
        React.unmountComponentAtNode(domNode);

        $(domNode).remove();
      }
    },
    render: function() {
      return (
        <div>
          <div className="modal" onClick={this.destroy}>
            <div className="modal-dialog">
              <div className="modal-content">
                <div className="modal-body">
                  {this.props.children}
                </div>
              </div>
            </div>
          </div>
          <div className="modal-backdrop fade in"></div>
        </div>
      );
    }
  });
});
