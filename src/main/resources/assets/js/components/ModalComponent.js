/** @jsx React.DOM */

define([
  "jquery",
  "React"
], function($, React) {
  function modalSizeClassName(size) {
    return (size == null) ? "" : "modal-" + size;
  }

  return React.createClass({
    componentDidMount: function() {
      var _this = this;

      // Destroy the modal when "ESC" key is pressed.
      $(document).on("keyup.modalComponent", function(event) {
        if (event.keyCode === 27) { _this.destroy(); }
      });

      this.timeout = setTimeout(this.transitionIn, 10);
    },
    componentWillUnmount: function() {
      $(document).off(".modalComponent");
    },
    destroy: function(event) {
      var domNode = this.getDOMNode();
      this.props.onDestroy();
      React.unmountComponentAtNode(domNode.parentNode);
    },
    getDefaultProps: function() {
      return {
        onDestroy: $.noop,
        size: null
      };
    },
    onClick: function(event) {
      var $target = $(event.target);

      if ($target.hasClass("modal") || $target.hasClass("modal-dialog")) {
        this.destroy();
      }
    },
    render: function() {
      var modalClassName =
        "modal-dialog " + modalSizeClassName(this.props.size);

      return (
        <div>
          <div className="modal fade" onClick={this.onClick} ref="modal"
              role="dialog" aria-hidden="true" tabIndex="-1">
            <div className={modalClassName}>
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
