/** @jsx React.DOM */

define([
  "React",
  "models/App",
  "jsx!components/NewAppModalComponent"
], function(React, App, NewAppModalComponent) {
  return React.createClass({
    displayName: "NewAppButtonComponent",

    propTypes: {
      collection: React.PropTypes.object.isRequired
    },

    saveModel: function() {
      this.props.collection.create(this.state.model);
    },

    showModal: function(event) {
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
          <NewAppModalComponent model={model} onCreate={this.saveModel} />,
          document.getElementById("lightbox")
        )
      });
    },

    render: function() {
      return (
        <button type="button" className="btn btn-primary header-btn" onClick={this.showModal}>
          + New App
        </button>
      );
    }
  });
});
