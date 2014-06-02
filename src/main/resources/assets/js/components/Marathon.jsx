/** @jsx React.DOM */

define([
  "mousetrap",
  "React",
  "models/App",
  "models/AppCollection",
  "jsx!components/AppListComponent",
  "jsx!components/NewAppModalComponent"
], function(Mousetrap, React, App, AppCollection, AppListComponent,
    NewAppModalComponent) {

  return React.createClass({
    displayName: "Marathon",

    getInitialState: function() {
      return {
        collection: new AppCollection()
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
        this.refs.appList.destroyActiveApp();
      }.bind(this));
    },

    handleModalCreate: function() {
      this.state.collection.create(this.state.model);
      this.setState({modal: null, model: null});
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

      var model = new App();
      this.setState({
        model: model,
        modal: React.renderComponent(
          <NewAppModalComponent model={model} onCreate={this.handleModalCreate} />,
          document.getElementById("lightbox")
        )
      });
    },

    render: function() {
      return (
        <div className="container-fluid">
          <div className="row">
            <div className="col-sm-6">
              <h1>Marathon / <i className="system-caret"></i></h1>
            </div>
            <div className="col-sm-6 text-right">
              <button type="button" className="btn btn-primary header-btn"
                  onClick={this.showNewAppModal}>
                + New App
              </button>
            </div>
          </div>
          <AppListComponent collection={this.state.collection} ref="appList" />
        </div>
      );
    }
  });
});
