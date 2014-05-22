/** @jsx React.DOM */

define([
  "React",
  "models/App",
  "jsx!components/NewAppModalComponent"
], function(React, App, NewAppModalComponent) {
  return React.createClass({
    saveModel: function() {
      this.props.collection.create(this.state.model);
    },
    showModal: function(event) {
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
