/** @jsx React.DOM */

define([
  "React",
  "models/App",
  "jsx!components/NewAppModalComponent"
], function(React, App, NewAppModalComponent) {
  return React.createClass({
    saveModel: function(attributes) {
      this.state.model.set(attributes);
      this.props.collection.create(this.state.model, {wait: true});
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
        <button type="button" className="btn btn-primary btn-lg add-button" onClick={this.showModal}>
          + New
        </button>
      );
    }
  });
});
