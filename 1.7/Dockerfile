FROM buildpack-deps:stretch-curl

COPY Gemfile Gemfile.lock /jekyll/

RUN apt-get update && \
  curl -sL https://deb.nodesource.com/setup_6.x | bash - && \
  apt-get install -y gcc g++ git libxml2 zlib1g-dev libxml2-dev ruby ruby-dev make autoconf nodejs python python-dev && \
  gem install bundler && \
  cd /jekyll && bundle install && \
  apt-get purge -y gcc g++ ruby-dev python-dev && \
  apt-get -y autoremove && \
  rm -rf /var/lib/apt/lists

COPY entrypoint.sh /

VOLUME ["/site-docs"]

ENTRYPOINT ["/entrypoint.sh"]

# Needed so sass can handle UTF-8 characters, and not die with 'Invalid US-ASCII character "\xE2"' messages.
# See https://github.com/jekyll/jekyll/issues/4268
ENV LC_ALL C.UTF-8
ENV LANG en_US.UTF-8
ENV LANGUAGE en_US.UTF-8
