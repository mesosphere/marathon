/** @jsx React.DOM */

define([
  "mousetrap",
  "React",
  "models/AppCollection",
  "jsx!components/AppListComponent",
  "jsx!components/NewAppModalComponent"
], function(Mousetrap, React, AppCollection, AppListComponent,
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

    handleModalCreate: function(appModel) {
      this.state.collection.create(appModel);
    },

    handleModalDestroy: function() {
      this.setState({modal: null});
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

      this.setState({
        modal: React.renderComponent(
          <NewAppModalComponent
            onCreate={this.handleModalCreate}
            onDestroy={this.handleModalDestroy} />,
          document.getElementById("lightbox")
        )
      });
    },

    render: function() {
      return (
        <div className="container-fluid">
          <div className="brand-header row">
            <div className="col-sm-12">
              <img className="marathon-logo" width="27" height="27" alt="Marathon Logo" src="../img/marathon-logo.svg" />
              <span className="h1 text-align-top">
                Marathon
              </span>
              <span className="pull-right">
                <button type="button" className="btn btn-success header-btn"
                    onClick={this.showNewAppModal}>
                  + New App
                </button>
              </span>
            </div>
          </div>
          <AppListComponent collection={this.state.collection} ref="appList" />
        </div>
      );
    }
  });
});
