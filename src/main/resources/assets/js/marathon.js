/**
 * fastLiveFilter jQuery plugin 1.0.3
 *
 * Copyright (c) 2011, Anthony Bush
 * License: <http://www.opensource.org/licenses/bsd-license.php>
 * Project Website: http://anthonybush.com/projects/jquery_fast_live_filter/
 **/

jQuery.fn.fastLiveFilter = function(list, options) {
  // Options: input, list, timeout, callback
  options = options || {};
  list = jQuery(list);
  var input = this;
  var timeout = options.timeout || 0;
  var callback = options.callback || function() {};

  var keyTimeout;

  // NOTE: because we cache lis & len here, users would need to re-init the plugin
  // if they modify the list in the DOM later.  This doesn't give us that much speed
  // boost, so perhaps it's not worth putting it here.
  var lis = $('.app-list-item');
  var len = lis.length;
  var oldDisplay = len > 0 ? lis[0].style.display : "block";
  callback(len); // do a one-time callback on initialization to make sure everything's in sync

  input.change(function() {
    // var startTime = new Date().getTime();
    var filter = input.val().toLowerCase();
    var li;
    var numShown = 0;

    var show = [];
    for (var i = 0; i < len; i++) {
      oli = lis[i];
      li = $(oli).find('h1')[0];
      if ((li.textContent || li.innerText || "").toLowerCase().indexOf(filter) >= 0) {
        if (oli.style.display == "none") {
          oli.style.display = oldDisplay;
        }
        show.push(window.all.get(oli.classList[1]));
        numShown++;
      } else {
        if (oli.style.display != "none") {
          oli.style.display = "none";
        }
      }
    }
    callback(numShown, show);
    // var endTime = new Date().getTime();
    // console.log('Search for ' + filter + ' took: ' + (endTime - startTime) + ' (' + numShown + ' results)');
    return false;
  }).keydown(function() {
    // TODO: one point of improvement could be in here: currently the change event is
    // invoked even if a change does not occur (e.g. by pressing a modifier key or
    // something)
    clearTimeout(keyTimeout);
    keyTimeout = setTimeout(function() { input.change(); }, timeout);
  });
  return this; // maintain jQuery chainability
};

(function(){

  (function(exports, Backbone){

    exports.Backpack = exports.Backpack || {};
    exports.Backpack.Models = exports.Backpack.Models || {};

    LightboxModel = Backbone.Model.extend({

      defaults: {
        'open': false,
        'lock': false,
        'backgroundColor': 'rgba(0,0,0,0.9)'
      },

      setContent: function(content){
        this.set('content', content);
      },

      open: function(){
        this.set('open', true);
      },

      close: function(){
        this.trigger('close');
        this.set('open', false);
      },

      dismiss: function(){
        if (!this.get('lock')) {
          this.close();
        }
      },

      lock: function(){
        this.set('lock', true);
      },

      unlock: function(){
        this.set('lock', false);
      },

      color: function(color){
        this.set('backgroundColor', color);
      }

    });

    exports.Backpack.Models.Lightbox = LightboxModel;

  })(window, Backbone);

  (function(exports, $, _, Backbone){
    var Lightbox;

    exports.Backpack = exports.Backpack || {};
    exports.Backpack.Models = exports.Backpack.Models || {};

    Lightbox = Backbone.View.extend({

      template:  _.template(
        "<div class='lightbox-inner'>" +
          "<div class='lb-content'></div>" +
        "</div>"),
      className: 'lightbox',
      events: {
        'click': 'dismiss',
        'click .lb-content': 'noop',
        'click [data-lightbox-close]': 'close'
      },

      bindings: function(){
        this.model.on('change:open', this.toggle, this);
        this.model.on('change:content', this.updateContent, this);
        this.model.on('change:backgroundColor', this.updateColor, this);
      },

      initialize: function(){
        this.model = new Backpack.Models.Lightbox;
        this.bindings();
        this.toggle();
        this.append();
        if (this.options.content) {
          this.content(this.options.content);
        }
      },

      render: function(){
        var template = this.template();
        this.$el.html(template);
        return this;
      },

      content: function(content){
        this.model.setContent(content);
        return this;
      },

      updateContent: function(){
        var content = this.model.get('content');
        var el = content.render().el;
        this.$content = this.$el.find('.lb-content');
        this.$content.html(el);
      },

      updateColor: function(){
        var color = this.model.get('backgroundColor');
        this.$el.css('background-color', color);
      },

      color: function(color){
        this.model.color(color);
      },

      append: function(){
        this.render();
        $('body').append(this.$el);
      },

      toggle: function(){
        var open = this.model.get('open');
        this.$el.toggle(open);
      },

      lock: function(){
        this.model.lock();
        return this;
      },

      unlock: function(){
        this.model.unlock();
        return this;
      },

      open: function(event){
        this.model.open();
        return this;
      },

      close: function(event){
        this.model.close();
        return this;
      },

      dismiss: function(event){
        this.model.dismiss();
        return this;
      },

      noop: function(event){
        event.stopPropagation();
      }

    });

    exports.Backpack.Lightbox = Lightbox;

  })(window, jQuery, _, Backbone);



  // Mustache style templats {{ }}
  _.templateSettings = {
    interpolate : /\{\{(.+?)\}\}/g
  };

  Backbone.emulateHTTP = true;

  var $terminal = $('#terminal'),
      $setter = $('#setter'),
      $systemText = $('#system-text'),
      $promptText = $('#prompt-text'),
      caret = $('#caret');

  $terminal.click(function(e){
    $setter.focus();
  });

  $setter.keydown(function(e){
    key(this.value);
  });

  $setter.keyup(function(e){
    key(this.value);
  });

  $setter.keypress(function(e){
    key(this.value);
  });

  function key(text) {
    write(text);
    move(text.length);
  }

  function write(text) {
    $systemText.html(text);
  }

  function move(length) {

  }

  $setter.focus();

  var data = [
    {
        "cmd": "cd sinatra_test && /usr/bin/ruby hi.rb",
        "cpus": 0.1,
        "env": {},
        "id": "sinatra",
        "instances": 5,
        "mem": 10.0,
        "port": 13195,
        "uris": [
            "http://localhost:8888/sinatra_test.tgz"
        ]
    },
    {
        "cmd": "cd rails_test && bundle exec rails server --port $PORT",
        "cpus": 1.0,
        "env": {
            "RAILS_ENV": "production"
        },
        "id": "Monorail",
        "instances": 20,
        "mem": 400.0,
        "port": 13196,
        "uris": [
            "http://datacentercomputer.s3.amazonaws.com/rails_test_app_1.9.tgz"
        ]
    },
    {
        "cmd": "cd rails_test && bundle exec rake resque:work",
        "cpus": 1.0,
        "env": {
            "RAILS_ENV": "production",
            "QUEUES": "*",
            "VERBOSE": "1"
        },
        "id": "Resque",
        "instances": 8,
        "mem": 400.0,
        "port": 14497,
        "uris": [
            "http://datacentercomputer.s3.amazonaws.com/rails_test_app_1.9.tgz"
        ]
    }
  ];

  var Item = Backbone.Model.extend({
    url: 'v1/apps/start',

    defaults: function() {
      return {
        id: _.uniqueId('app_'),
        cmd: 'sleep 10',
        mem: 10.0,
        cpus: 0.1,
        instances: 1,
        uris: []
      };
    },

    sync: function(method, model, options) {
      options = options || {};

      if (method === 'delete') {
        options = _.extend(options, {
          url: 'v1/apps/stop',
          contentType: 'application/json',
          data: JSON.stringify(options.attrs || model.toJSON(options))
        });
      } else if (method === 'scale') {
        method = 'create';
        options = _.extend(options, {
          url: 'v1/apps/scale',
          contentType: 'application/json',
          data: JSON.stringify(options.attrs || model.toJSON(options))
        });
      }

      Backbone.sync.apply(this, [method, model, options]);
    },

    scale: function(num, options) {
      options = options || {};
      this.set('instances', num);
      this.sync('scale', this, options);
    }
  });


  var Items = Backbone.Collection.extend({
    url: 'v1/apps/',
    model: Item,
  });

  window.ItemView = Backbone.View.extend({
    tagName: 'li',

    template: _.template(
        "{{ id }}"
    ),

    events: {
      'click': 'select'
    },

    initialize: function() {

    },

    render: function() {
      var data = this.data()
          html = this.template(data);
      this.$el.append(html);
      return this.$el;
    },

    data: function() {
      return this.model.toJSON();
    },

    select: function() {
      console.log(this.model.get('id'));
      this.model.set('selected', true);
    }
  });

  var ItemsView = Backbone.View.extend({
    tagName: 'ul',

    events: {
      'click .add': 'addNew',
      'change:selected': 'details'
    },

    initialize: function() {
      this.collection.on('reset', this.renderAll, this);
      this.collection.on('add', this.append, this);
    },

    render: function() {
      this.$add = $("<li class='add'>+</li>");
      this.$el.append(this.$add)
      return this.$el;
    },

    renderAll: function() {
      var html = '',
          view;

      this.collection.each(function(a){
        view = new ItemView({model: a});
        this.$el.prepend(view.render());
      }, this);
    },

    addNew: function() {
      // var model = new Item();
      // this.collection.add(model);
      $('.details').addClass('open')
      this.$el.slideUp(400, function(){
        $promptText.html('New');
        $promptText.addClass('open');
        caret.hide();
      });
    },

    append: function(model) {
      model.set('id', model.cid)
      var view = new ItemView({model:model});
      this.$add.before(view.render());
    },

    details: function(model) {
      $('.details').addClass('open')
      this.$el.slideUp(200, function(){
        $promptText.html('New');
        $promptText.show();
        caret.hide();
      });
    }
  });


  var AppItemView = Backbone.View.extend({
    tagName: 'li',
    className: 'app-list-item',
    template: _.template(
      "<div class='app-item'>" +
        "<div class='info-wrapper'>" +
          "<h1>{{ id }}</h1>" +
          "CMD: <span class='val'>{{ cmd }}</span><br/>" +
          "<div class='uri-wrapper'>URIs: <span class='val'>{{ uriCount }}</span>" +
            "<ul class='uris'>{{uris}}</ul>" +
          "</div>" +
          "Memory: <span class='val'>{{ mem }}</span><br/>" +
          "CPU: <span class='val'>{{ cpus }}<br/></span>" +
          "Instances: <span class='val'>{{ instances }}</span><br/>" +
        "</div>" +
        "<div class='action-bar'>" +
          "<a class='scale' href='#'>SCALE</a>  | " +
          "<a class='suspend' href='#'>SUSPEND</a>  | " +
          "<a class='destroy' href='#'>DESTROY</a>" +
        "</div>" +
      "</div>"
    ),

    events: {
      'click .suspend': 'suspend',
      'click .destroy': 'destroy',
      'click .scale': 'scale'
    },

    initialize: function() {
      this.model.on('destroy', this.remove, this);
      this.model.on('change:instances', this.render, this);
      this.$el.addClass(this.model.get('id'));
    },

    suspend: function(e) {
      if (confirm("Suspend " + this.model.id + "?\n\nThe application will be scaled to 0 instances.")) {
        this.model.scale(0);
      }

      e.preventDefault();
    },

    destroy: function(e) {
      var ok = confirm("Destroy application " + this.model.id + "?\n\nThis is irreversible.");
      if (ok) {
        this.model.destroy();
      }

      e.preventDefault();
    },

    scale: function(e) {
      var instances = prompt('How many instances?', this.model.get('instances'));
      if (instances) {
        this.model.scale(instances);
      }

      e.preventDefault();
    },

    remove: function() {
      this.$el.remove();
    },

    render: function() {
      var data = this.data(),
          html = this.template(data);
      this.$el.html(html);
      return this;
    },

    data: function() {
      var attr = this.model.toJSON(),
          total = (attr.cpus * attr.instances),
          uriCount = attr.uris.length,
          uris = (attr.uris.join('<li>'));
      attr = _.extend(attr, {
        total: total,
        uris: uris,
        uriCount: uriCount
      });
      return attr;
    }
  });

  window.HomeView = Backbone.View.extend({
    tagName: 'ul',
    className: 'start-view-list list-unstyled',

    events: {
      'click .add-button': 'addNew',
    },

    addButton: function() {
      return $("<li><div class='app-item add-button'>+</div></li>");
    },

    initialize: function(){
      this.$addButton = this.addButton();
      this.$el.append(this.$addButton);
      this.collection.on('add', this.add, this);
      this.collection.on('reset', this.render, this);
      // TODO: fix the clean up
      // window.lightbox.model.on('close', this.dismiss, this);
    },

    render: function(){
      this.collection.each(function(model){
        this.add(model);
      }, this);
      return this;
    },

    add: function(model, collection, options) {
      var view = new AppItemView({ model: model });
      this.$addButton.before(view.render().el);
    },

    addNew: function() {
      var model = new Item(),
          collection = this.collection;
      var FormView = Backbone.View.extend({
        className: 'window',
        template: _.template($('#add-app-template').html()),

        events: {
          'click #save': 'save'
        },

        render: function() {
          console.log(model.toJSON());
          this.$el.html(this.template(model.toJSON()));
          return this;
        },

        save: function(e) {
          e.preventDefault();
          e.stopPropagation();
          var $inputs = $('#add-app-form').find('input');
          var data = {};

          $inputs.each(function(index, el){
            var $el = $(el),
                name = $el.attr('name'),
                val = $el.val();

                if (name === 'uris') {
                  val = val.split(',');
                  // strip whitespace
                  val = _.map(val, function(s){return s.replace(/ /g,''); });
                  // reject empty
                  val = _.reject(val, function(s){return s===''});
                }

                data[name] = val;
          });

          collection.create(data);
          window.lightbox.close();
        }
      });
      formView = new FormView();
      window.lightbox.content(formView);
      window.lightbox.open();
      $('#id-field').focus();
    },

    dismiss: function() {
      var model = formView.model;
      if (model.isNew()) {
        console.log('is new');
        model.destroy();
      } else {
        console.log('adding to collection')
        this.collection.add(model);
      }
    },
  });


  var Router = Backbone.Router.extend({
    routes: {
      'home': 'home'
    },

    initialize: function() {
      window.apps = new Items;
      window.lightbox = new Backpack.Lightbox();
      window.start = new window.HomeView({
        collection: apps
      });

      $('.content').append(start.render().el);
      // window.appsView = new ItemsView({
      //   collection: apps
      // });

      // $('.list').html(window.appsView.render());
      var All = Backbone.Collection.extend({});

      apps
        .fetch({reset: true})
        .done(function(){
          window.all = new All(apps.models);
          $input = $('#setter');
          $caret = $('.system-caret');

          $input.fastLiveFilter('.start-view-list' , {
            callback: function(total, results) {
              // do something if you like
            }
          });

          $input.focusin(function(){
            $caret.addClass('focus');
          });

          $input.focusout(function(){
            $caret.removeClass('focus');
          });
      });
    },

    home: function() {
      console.log('home');
    }

  });

  window.router = new Router();
  Backbone.history.start({
    pushState: true
  });

})();