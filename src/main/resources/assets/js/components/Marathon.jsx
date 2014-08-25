/** @jsx React.DOM */

define([
  "mousetrap",
  "React",
  "Underscore",
  "models/AppCollection",
  "jsx!components/AppListComponent",
  "jsx!components/AppModalComponent",
  "jsx!components/NewAppModalComponent"
], function(Mousetrap, React, _, AppCollection, AppListComponent,
    AppModalComponent, NewAppModalComponent) {
  "use strict";

  var STATES = {
    STATE_LOADING: 0,
    STATE_ERROR: 1,
    STATE_SUCCESS: 2
  };

  var UPDATE_INTERVAL = 5000;

  return React.createClass({
    displayName: "Marathon",

    getInitialState: function() {
      return {
        collection: new AppCollection(),
        fetchState: STATES.STATE_LOADING
      };
    },

    componentDidMount: function() {
      // Override Mousetrap's `stopCallback` to allow "esc" to trigger even within
      // input elements so the new app modal can be closed via "esc".
      var mousetrapOriginalStopCallback = Mousetrap.stopCallback;
      Mousetrap.stopCallback = function(e, element, combo) {
        if (combo === "esc" || combo === "escape") { return false; }
        return mousetrapOriginalStopCallback.apply(null, arguments);
      };

      Mousetrap.bind("c", function() {
        this.showNewAppModal(); }.bind(this), "keyup");
      Mousetrap.bind("#", function() {
        if (this.state.modal != null &&
            _.isFunction(this.state.modal.destroyApp)) {
          this.state.modal.destroyApp();
        }
      }.bind(this));

      this.startPolling();
    },

    componentWillUnmount: function() {
      this.stopPolling();
    },

    fetchResource: function() {
      var _this = this;

      this.state.collection.fetch({
        error: function() {
          _this.setState({fetchState: STATES.STATE_ERROR});
        },
        reset: true,
        success: function() {
          _this.setState({fetchState: STATES.STATE_SUCCESS});
        }
      });
    },

    handleAppCreate: function(appModel, options) {
      this.state.collection.create(appModel, options);
    },

    handleModalDestroy: function() {
      this.setState({modal: null}, function() {
        this.refs.appList.startPolling();
      }.bind(this));
    },

    startPolling: function() {
      if (this._interval == null) {
        this.fetchResource();
        this._interval = setInterval(this.fetchResource, UPDATE_INTERVAL);
      }
    },

    stopPolling: function() {
      if (this._interval != null) {
        clearInterval(this._interval);
        this._interval = null;
      }
    },

    showAppModal: function(app) {
      if (this.state != null && this.state.modal != null &&
          this.state.modal.isMounted()) {
        return;
      }

      /* jshint trailing:false, quotmark:false, newcap:false */
      this.setState({
        modal: React.renderComponent(
          <AppModalComponent model={app} onDestroy={this.handleModalDestroy} />,
          document.getElementById("lightbox"),
          this.stopPolling
        )
      });
    },

    showNewAppModal: function(event) {
      // Don't recreate the modal on successive calls of `showModal` if the
      // modal is already open. For example, pressing "c" to open the modal and
      // then pressing "c" again should not create new App and Modal instances
      // or data will be lost if the form is partially filled.
      if (this.state != null && this.state.modal != null &&
          this.state.modal.isMounted()) {
        return;
      }

      /* jshint trailing:false, quotmark:false, newcap:false */
      this.setState({
        modal: React.renderComponent(
          <NewAppModalComponent
            onCreate={this.handleAppCreate}
            onDestroy={this.handleModalDestroy} />,
          document.getElementById("lightbox"),
          this.stopPolling
        )
      });
    },

    render: function() {
      /* jshint trailing:false, quotmark:false, newcap:false */
      return (
        <div>
          <nav className="navbar navbar-inverse" role="navigation">
           <div className="container-fluid">
              <a className="navbar-brand" href="/">
                <img width="160" height="27" alt="Marathon" src="/img/marathon-logo.png" />
              </a>
              <button type="button" className="btn btn-success navbar-btn pull-right"
                  onClick={this.showNewAppModal}>
                + New App
              </button>
            </div>
          </nav>
          <div className="container-fluid">
            <AppListComponent
              collection={this.state.collection}
              onSelectApp={this.showAppModal}
              fetchState={this.state.fetchState}
              STATES={STATES}
              ref="appList" />
          </div>
        </div>
      );
    }
  });
});
