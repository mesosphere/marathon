FROM ruby:2.5

ARG FPM_VERSION=1.10.2
RUN apt-get update && apt-get install -y rpm
RUN gem install fpm --version=${FPM_VERSION}
RUN mkdir /build

CMD ["fpm"]
WORKDIR /build
