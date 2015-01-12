/** @jsx React.DOM */


var $ = require("jquery");
var React = require("react");

  function modalSizeClassName(size) {
    return (size == null) ? "" : "modal-" + size;
  }

module.exports = React.createClass({
    displayName: "ModalComponent",
    propTypes: {
      onDestroy: React.PropTypes.func,
      size: React.PropTypes.string
    },

    componentDidMount: function() {
      this.timeout = setTimeout(this.transitionIn, 10);
    },

    destroy: function() {
      this.props.onDestroy();
    },

    getDefaultProps: function() {
      return {
        onDestroy: $.noop,
        size: null
      };
    },

    getInitialState: function() {
      return {
        isIn: false
      };
    },

    onClick: function(event) {
      var $target = $(event.target);

      if ($target.hasClass("modal") || $target.hasClass("modal-dialog")) {
        this.destroy();
      }
    },

    transitionIn: function() {
      this.setState({isIn: true});
    },

    render: function() {
      var modalDialogClassName =
        "modal-dialog " + modalSizeClassName(this.props.size);

      var modalBackdropClassName = React.addons.classSet({
        "modal-backdrop fade": true,
        "in": this.state.isIn
      });

      var modalClassName = React.addons.classSet({
        "modal fade": true,
        "in": this.state.isIn
      });

      /* jshint trailing:false, quotmark:false, newcap:false */
      return (
        <div>
          <div className={modalClassName}
              onClick={this.onClick}
              role="dialog"
              aria-hidden="true"
              tabIndex="-1">
            <div className={modalDialogClassName}>
              <div className="modal-content">
                {this.props.children}
              </div>
            </div>
          </div>
          <div className={modalBackdropClassName}></div>
        </div>
      );
    }
  });
