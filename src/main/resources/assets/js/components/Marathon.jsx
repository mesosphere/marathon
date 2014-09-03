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

  return React.createClass({
    displayName: "Marathon",

    getInitialState: function() {
      return {
        collection: new AppCollection(),
        modalClass: null
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

      Mousetrap.bind("esc", function() {
        if (this.refs.modal != null) {
          this.refs.modal.destroy();
        }
      }.bind(this));

      Mousetrap.bind("c", function() {
        this.showNewAppModal(); }.bind(this), "keyup");

      Mousetrap.bind("#", function() {
        if (this.state.modalClass === AppModalComponent &&
            _.isFunction(this.refs.modal.destroyApp)) {
          this.refs.modal.destroyApp();
        }
      }.bind(this));
    },

    componentDidUpdate: function(prevProps, prevState) {
      if (prevState.modalClass !== this.state.modalClass) {
        // No `modalClass` means the modal went from open to closed. Start
        // polling in that case, otherwise stop polling since the modal went
        // from closed to open.
        this.state.modalClass === null ?
          this.refs.appList.startPolling() :
          this.refs.appList.stopPolling();
      }
    },

    handleAppCreate: function(appModel, options) {
      this.state.collection.create(appModel, options);
    },

    handleModalDestroy: function() {
      this.setState({
        activeApp: null,
        modalClass: null
      });
    },

    showAppModal: function(app) {
      if (this.state.modalClass !== null) {
        return;
      }

      this.setState({
        activeApp: app,
        modalClass: AppModalComponent
      });
    },

    showNewAppModal: function(event) {
      if (this.state.modalClass !== null) {
        return;
      }

      this.setState({
        modalClass: NewAppModalComponent
      });
    },

    render: function() {
      var modal;
      if (this.state.modalClass !== null) {
        if (this.state.modalClass === AppModalComponent) {
          modal = (
            <AppModalComponent
              model={this.state.activeApp}
              onDestroy={this.handleModalDestroy}
              ref="modal" />
          );
        } else if (this.state.modalClass === NewAppModalComponent) {
          modal = (
            <NewAppModalComponent
             onCreate={this.handleAppCreate}
             onDestroy={this.handleModalDestroy}
             ref="modal" />
          );
        }
      }

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
              ref="appList" />
          </div>
          {modal}
        </div>
      );
    }
  });
});
