/** @jsx React.DOM */

define([
  "jquery",
  "Underscore",
  "mousetrap",
  "React"
], function($, _, Mousetrap, React) {
  "use strict";

  function modalSizeClassName(size) {
    return (size == null) ? "" : "modal-" + size;
  }

  return React.createClass({
    displayName: "ModalComponent",
    propTypes: {
      onDestroy: React.PropTypes.func,
      size: React.PropTypes.string
    },

    componentDidMount: function() {
      Mousetrap.bind("esc", this.destroy);
      this.timeout = setTimeout(this.transitionIn, 10);
    },

    componentWillUnmount: function() {
      Mousetrap.unbind("esc");
    },

    destroy: function(event) {
      var domNode = this.getDOMNode();
      this.props.onDestroy();

      // Let the current call stack clear so ancestor components accessing this
      // modal can still access it before it is unmounted. Without deferring,
      // React throws an exception in `ReactMount.findComponentRoot`.
      _.defer(function() {
        React.unmountComponentAtNode(domNode.parentNode);
      });
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

    transitionIn: function() {
      this.refs.modal.getDOMNode().className += " in";
      this.refs.backdrop.getDOMNode().className += " in";
    },

    render: function() {
      var modalClassName =
        "modal-dialog " + modalSizeClassName(this.props.size);
      /* jshint trailing:false, quotmark:false, newcap:false */
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
    }
  });
});
