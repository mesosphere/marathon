/** @jsx React.DOM */

define([
  "jquery",
  "React"
], function($, React) {
  return React.createClass({
    componentDidMount: function() {
      var _this = this;

      // Destroy the modal when "ESC" key is pressed.
      $(document).on("keyup.modalComponent", function(event) {
        if (event.keyCode === 27) { _this.destroy(); }
      });

      this.timeout = setTimeout(this.transitionIn, 0);
    },
    componentWillUnmount: function() {
      $(document).off(".modalComponent");
    },
    destroy: function(event) {
      var domNode = this.getDOMNode();
      if (this.props.onDestroy != null) this.props.onDestroy();

      // TODO(ssorallen): Why does this need to unmount from the parentNode?
      // If it is unmounted from `domNode`, the second render throws an
      // invariant exception.
      React.unmountComponentAtNode(domNode.parentNode);
    },
    onClick: function(event) {
      var $target = $(event.target);

      if ($target.hasClass("modal") || $target.hasClass("modal-dialog")) {
        this.destroy();
      }
    },
    render: function() {
      return (
        <div>
          <div className="modal fade" onClick={this.onClick} ref="modal">
            <div className="modal-dialog">
              <div className="modal-content">
                {this.props.children}
              </div>
            </div>
          </div>
          <div className="modal-backdrop fade" ref="backdrop"></div>
        </div>
      );
    },
    transitionIn: function() {
      this.refs.modal.getDOMNode().className += " in";
      this.refs.backdrop.getDOMNode().className += " in";
    }
  });
});
