/** @jsx React.DOM */

define([
  "jquery",
  "React"
], function($, React) {
  "use strict";

  return React.createClass({
    displayName: "PageComponent",
    propTypes: {
      onDestroy: React.PropTypes.func
    },

    componentDidMount: function() {
      this.timeout = setTimeout(this.transitionIn, 10);
    },

    destroy: function() {
      this.props.onDestroy();
    },

    getDefaultProps: function() {
      return {
        onDestroy: $.noop
      };
    },

    getInitialState: function() {
      return {
        isIn: false
      };
    },

    onClick: function(event) {
      var $target = $(event.target);

      if ($target.hasClass("page") || $target.hasClass("page-dialog")) {
        this.destroy();
      }
    },

    transitionIn: function() {
      this.setState({isIn: true});
    },

    render: function() {
      var pageDialogClassName =
        "page-dialog ";

      var pageBackdropClassName = React.addons.classSet({
        "page-backdrop fade": true,
        "in": this.state.isIn
      });

      var pageClassName = React.addons.classSet({
        "page fade": true,
        "in": this.state.isIn
      });

      /* jshint trailing:false, quotmark:false, newcap:false */
      return (
        <div>
          <div className={pageClassName}
              onClick={this.onClick}
              role="dialog"
              aria-hidden="true"
              tabIndex="-1">
            <div className={pageDialogClassName}>
              <div className="page-content">
                {this.props.children}
              </div>
            </div>
          </div>
          <div className={pageBackdropClassName}></div>
        </div>
      );
    }
  });
});
