FROM buildpack-deps:stretch-curl

COPY Gemfile Gemfile.lock /jekyll/

RUN apt-get update && \
  curl -sL https://deb.nodesource.com/setup_6.x | bash - && \
  apt-get install -y gcc git libxml2 zlib1g-dev libxml2-dev ruby ruby-dev make autoconf nodejs python python-dev && \
  gem install bundler && \
  cd /jekyll && bundle install && \
  apt-get purge -y gcc ruby-dev python-dev && \
  apt-get -y autoremove && \
  rm -rf /var/lib/apt/lists

COPY entrypoint.sh /

EXPOSE 4000
VOLUME ["/marathon-docs"]

ENTRYPOINT /entrypoint.sh
